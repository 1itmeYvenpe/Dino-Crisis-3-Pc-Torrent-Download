package dotty.tools.dotc
package transform

import core._
import TreeTransforms._
import collection.mutable
import SymDenotations._, Symbols._, Contexts._, Types._, Names._, StdNames._, NameOps._
import ast.Trees._
import util.Positions._
import typer.Applications.{isProductMatch, isGetMatch, productSelectors}
import SymUtils._
import Flags._, Constants._
import Decorators._
import patmat.Space
import NameKinds.{UniqueNameKind, PatMatStdBinderName, PatMatCaseName}
import config.Printers.patmatch

/** The pattern matching transform.
 *  After this phase, the only Match nodes remaining in the code are simple switches
 *  where every pattern is an integer constant
 */
class PatternMatcher extends MiniPhaseTransform {
  import ast.tpd._
  import PatternMatcher._

  override def phaseName = "patternMatcher"
  override def runsAfter = Set(classOf[ElimRepeated])
  override def runsAfterGroupsOf = Set(classOf[TailRec]) // tailrec is not capable of reversing the patmat tranformation made for tree

  override def transformMatch(tree: Match)(implicit ctx: Context, info: TransformerInfo): Tree = {
    val translated = new Translator(tree.tpe, this).translateMatch(tree)

    // check exhaustivity and unreachability
    val engine = new patmat.SpaceEngine

    if (engine.checkable(tree)) {
      engine.checkExhaustivity(tree)
      engine.checkRedundancy(tree)
    }

    translated.ensureConforms(tree.tpe)
  }
}

object PatternMatcher {
  import ast.tpd._

  final val selfCheck = false // debug option, if on we check that no case gets generated twice

  /** Was symbol generated by pattern matcher? */
  def isPatmatGenerated(sym: Symbol)(implicit ctx: Context): Boolean =
    sym.is(Synthetic) &&
    (sym.name.is(PatMatStdBinderName) || sym.name.is(PatMatCaseName))

  /** The pattern matching translator.
   *  Its general structure is a pipeline:
   *
   *     Match tree ---matchPlan---> Plan ---optimize---> Plan ---emit---> Tree
   *
   *  The pipeline consists of three steps:
   *
   *    - build a plan, using methods `matchPlan`, `caseDefPlan`, `patternPlan`.
   *    - optimize the plan, using methods listed in `optimization`,
   *    - emit the translated tree, using methods `emit`, `collectSwitchCases`,
   *      `emitSwitchCases`, and `emitCondition`.
   *
   *  A plan represents the underlying decision graph. It consists
   *  of tests, let and label bindings, calls to labels and code blocks.
   *  It's represented by its own data type. Plans are optimized by
   *  inlining, hoisting, and the elimination of redundant tests and dead code.
   */
  class Translator(resultType: Type, trans: TreeTransform)(implicit ctx: Context, info: TransformerInfo) {

    // ------- Bindings for variables and labels ---------------------

    /** A map from variable symbols to their defining trees
     *  and from labels to their defining plans
     */
    private val initializer = mutable.Map[Symbol, Tree]()
    private val labelled = mutable.Map[Symbol, Plan]()

    private def newVar(rhs: Tree, flags: FlagSet): TermSymbol =
      ctx.newSymbol(ctx.owner, PatMatStdBinderName.fresh(), Synthetic | Case | flags,
        sanitize(rhs.tpe), coord = rhs.pos)
        // TODO: Drop Case once we use everywhere else `isPatmatGenerated`.

    /** The plan `let x = rhs in body(x)` where `x` is a fresh variable */
    private def letAbstract(rhs: Tree)(body: Symbol => Plan): Plan = {
      val vble = newVar(rhs, EmptyFlags)
      initializer(vble) = rhs
      LetPlan(vble, body(vble))
    }

    /** The plan `let l = labelled in body(l)` where `l` is a fresh label */
    private def labelAbstract(labeld: Plan)(body: (=> Plan) => Plan): Plan = {
      val label = ctx.newSymbol(ctx.owner, PatMatCaseName.fresh(), Synthetic | Label | Method,
        MethodType(Nil, resultType))
      labelled(label) = labeld
      LabelledPlan(label, body(CallPlan(label, Nil)), Nil)
    }

    /** Test whether a type refers to a pattern-generated variable */
    private val refersToInternal = new TypeAccumulator[Boolean] {
      def apply(x: Boolean, tp: Type) =
        x || {
          tp match {
            case tp: TermRef => isPatmatGenerated(tp.symbol)
            case _ => false
          }
        } || foldOver(x, tp)
    }

    /** Widen type as far as necessary so that it does not refer to a pattern-
     *  generated variable.
     */
    private def sanitize(tp: Type): Type = tp.widenExpr match {
      case tp: TermRef if refersToInternal(false, tp) => sanitize(tp.underlying)
      case tp => tp
    }

    // ------- Plan and test types ------------------------

    /** Counter to display plans nicely, for debugging */
    private[this] var nxId = 0

    /** The different kinds of plans */
    sealed abstract class Plan { val id = nxId; nxId += 1 }

    case class TestPlan(test: Test, var scrutinee: Tree, pos: Position,
                        var onSuccess: Plan, var onFailure: Plan) extends Plan {
      override def equals(that: Any) = that match {
        case that: TestPlan => this.scrutinee === that.scrutinee && this.test == that.test
        case _ => false
      }
      override def hashCode = scrutinee.hash * 41 + test.hashCode
    }

    case class LetPlan(sym: TermSymbol, var body: Plan) extends Plan
    case class LabelledPlan(sym: TermSymbol, var body: Plan, var params: List[TermSymbol]) extends Plan
    case class CodePlan(var tree: Tree) extends Plan
    case class CallPlan(label: TermSymbol,
                        var args: List[(/*formal*/TermSymbol, /*actual*/TermSymbol)]) extends Plan

    object TestPlan {
      def apply(test: Test, sym: Symbol, pos: Position, ons: Plan, onf: Plan): TestPlan =
        TestPlan(test, ref(sym), pos, ons, onf)
    }

    /** The different kinds of tests */
    sealed abstract class Test
    case class TypeTest(tpt: Tree) extends Test {                 // scrutinee.isInstanceOf[tpt]
      override def equals(that: Any) = that match {
        case that: TypeTest => this.tpt.tpe =:= that.tpt.tpe
        case _ => false
      }
      override def hashCode = tpt.tpe.hash
    }
    case class EqualTest(tree: Tree) extends Test {               // scrutinee == tree
      override def equals(that: Any) = that match {
        case that: EqualTest => this.tree === that.tree
        case _ => false
      }
      override def hashCode = tree.hash
    }
    case class LengthTest(len: Int, exact: Boolean) extends Test  // scrutinee (== | >=) len
    case object NonEmptyTest extends Test                         // !scrutinee.isEmpty
    case object NonNullTest extends Test                          // scrutinee ne null
    case object GuardTest extends Test                            // scrutinee

    // ------- Generating plans from trees ------------------------

    /** A set of variabes that are known to be not null */
    private val nonNull = mutable.Set[Symbol]()

    /** A conservative approximation of which patterns do not discern anything.
      * They are discarded during the translation.
      */
    private object WildcardPattern {
      def unapply(pat: Tree): Boolean = pat match {
        case Typed(_, tpt) if tpt.tpe.isRepeatedParam => true
        case Bind(nme.WILDCARD, WildcardPattern()) => true // don't skip when binding an interesting symbol!
        case t if isWildcardArg(t)                 => true
        case x: BackquotedIdent                    => false
        case x: Ident                              => x.name.isVariableName
        case Alternative(ps)                       => ps.forall(unapply)
        case EmptyTree                             => true
        case _                                     => false
      }
    }

    private object VarArgPattern {
      def unapply(pat: Tree): Option[Tree] = swapBind(pat) match {
        case Typed(pat1, tpt) if tpt.tpe.isRepeatedParam => Some(pat1)
        case _ => None
      }
    }

    /** Rewrite (repeatedly) `x @ (p: T)` to `(x @ p): T`
     *  This brings out the type tests to where they can be analyzed.
     */
    private def swapBind(tree: Tree): Tree = tree match {
      case Bind(name, pat0) =>
        swapBind(pat0) match {
          case Typed(pat, tpt) => Typed(cpy.Bind(tree)(name, pat), tpt)
          case _ => tree
        }
      case _ => tree
    }

    /** Plan for matching `scrutinee` symbol against `tree` pattern */
    private def patternPlan(scrutinee: Symbol, tree: Tree, onSuccess: Plan, onFailure: Plan): Plan = {

      /** Plan for matching `selectors` against argument patterns `args` */
      def matchArgsPlan(selectors: List[Tree], args: List[Tree], onSuccess: Plan): Plan =
        args match {
          case arg :: args1 =>
            val selector :: selectors1 = selectors
            letAbstract(selector)(
              patternPlan(_, arg, matchArgsPlan(selectors1, args1, onSuccess), onFailure))
          case Nil => onSuccess
        }

      /** Plan for matching the sequence in `seqSym` against sequence elements `args`.
       *  If `exact` is true, the sequence is not permitted to have any elements following `args`.
       */
      def matchElemsPlan(seqSym: Symbol, args: List[Tree], exact: Boolean, onSuccess: Plan) = {
        val selectors = args.indices.toList.map(idx =>
          ref(seqSym).select(nme.apply).appliedTo(Literal(Constant(idx))))
        TestPlan(LengthTest(args.length, exact), seqSym, seqSym.pos,
          matchArgsPlan(selectors, args, onSuccess), onFailure)
      }

      /** Plan for matching the sequence in `getResult` against sequence elements
       *  and a possible last varargs argument `args`.
       */
      def unapplySeqPlan(getResult: Symbol, args: List[Tree]): Plan = args.lastOption match {
        case Some(VarArgPattern(arg)) =>
          val matchRemaining =
            if (args.length == 1)
              patternPlan(getResult, arg, onSuccess, onFailure)
            else {
              val dropped = ref(getResult)
                .select(defn.Seq_drop.matchingMember(getResult.info))
                .appliedTo(Literal(Constant(args.length - 1)))
              letAbstract(dropped) { droppedResult =>
                patternPlan(droppedResult, arg, onSuccess, onFailure)
              }
            }
          matchElemsPlan(getResult, args.init, exact = false, matchRemaining)
        case _ =>
          matchElemsPlan(getResult, args, exact = true, onSuccess)
      }

      /** Plan for matching the result of an unapply against argument patterns `args` */
      def unapplyPlan(unapp: Tree, args: List[Tree]): Plan = {
        def caseClass = unapp.symbol.owner.linkedClass
        lazy val caseAccessors = caseClass.caseAccessors.filter(_.is(Method))

        def isSyntheticScala2Unapply(sym: Symbol) =
          sym.is(SyntheticCase) && sym.owner.is(Scala2x)

        if (isSyntheticScala2Unapply(unapp.symbol) && caseAccessors.length == args.length)
          matchArgsPlan(caseAccessors.map(ref(scrutinee).select(_)), args, onSuccess)
        else if (unapp.tpe.isRef(defn.BooleanClass))
          TestPlan(GuardTest, unapp, unapp.pos, onSuccess, onFailure)
        else {
          letAbstract(unapp) { unappResult =>
            val isUnapplySeq = unapp.symbol.name == nme.unapplySeq
            if (isProductMatch(unapp.tpe.widen, args.length) && !isUnapplySeq) {
              val selectors = productSelectors(unapp.tpe).take(args.length)
                .map(ref(unappResult).select(_))
              matchArgsPlan(selectors, args, onSuccess)
            }
            else {
              assert(isGetMatch(unapp.tpe))
              val argsPlan = {
                val get = ref(unappResult).select(nme.get, _.info.isParameterless)
                if (isUnapplySeq)
                  letAbstract(get)(unapplySeqPlan(_, args))
                else
                  letAbstract(get) { getResult =>
                    val selectors =
                      if (args.tail.isEmpty) ref(getResult) :: Nil
                      else productSelectors(get.tpe).map(ref(getResult).select(_))
                    matchArgsPlan(selectors, args, onSuccess)
                  }
              }
              TestPlan(NonEmptyTest, unappResult, unapp.pos, argsPlan, onFailure)
            }
          }
        }
      }

      // begin patternPlan
      swapBind(tree) match {
        case Typed(pat, tpt) =>
          TestPlan(TypeTest(tpt), scrutinee, tree.pos,
            letAbstract(ref(scrutinee).asInstance(tpt.tpe)) { casted =>
              nonNull += casted
              patternPlan(casted, pat, onSuccess, onFailure)
            },
            onFailure)
        case UnApply(extractor, implicits, args) =>
          val mt @ MethodType(_) = extractor.tpe.widen
          var unapp = extractor.appliedTo(ref(scrutinee).ensureConforms(mt.paramInfos.head))
          if (implicits.nonEmpty) unapp = unapp.appliedToArgs(implicits)
          val unappPlan = unapplyPlan(unapp, args)
          if (scrutinee.info.isNotNull || nonNull(scrutinee)) unappPlan
          else TestPlan(NonNullTest, scrutinee, tree.pos, unappPlan, onFailure)
        case Bind(name, body) =>
          if (name == nme.WILDCARD) patternPlan(scrutinee, body, onSuccess, onFailure)
          else {
            // The type of `name` may refer to val in `body`, therefore should come after `body`
            val bound = tree.symbol.asTerm
            initializer(bound) = ref(scrutinee)
            patternPlan(scrutinee, body, LetPlan(bound, onSuccess), onFailure)
          }
        case Alternative(alts) =>
          labelAbstract(onSuccess) { ons =>
            (alts :\ onFailure) { (alt, onf) =>
              labelAbstract(onf) { onf1 =>
                patternPlan(scrutinee, alt, ons, onf1)
              }
            }
          }
        case WildcardPattern() =>
          onSuccess
        case _ =>
          TestPlan(EqualTest(tree), scrutinee, tree.pos, onSuccess, onFailure)
      }
    }

    private def caseDefPlan(scrutinee: Symbol, cdef: CaseDef, onFailure: Plan): Plan =
      labelAbstract(onFailure) { onf =>
        var onSuccess: Plan = CodePlan(cdef.body)
        if (!cdef.guard.isEmpty)
          onSuccess = TestPlan(GuardTest, cdef.guard, cdef.guard.pos, onSuccess, onf)
        patternPlan(scrutinee, cdef.pat, onSuccess, onf)
      }

    private def matchPlan(tree: Match): Plan =
      letAbstract(tree.selector) { scrutinee =>
        val matchError: Plan = CodePlan(Throw(New(defn.MatchErrorType, ref(scrutinee) :: Nil)))
        (tree.cases :\ matchError)(caseDefPlan(scrutinee, _, _))
      }

    // ----- Optimizing plans ---------------

    /** A superclass for plan transforms */
    class PlanTransform extends (Plan => Plan) {
      protected val treeMap = new TreeMap {
        override def transform(tree: Tree)(implicit ctx: Context) = tree
      }
      def apply(tree: Tree): Tree = treeMap.transform(tree)
      def apply(plan: TestPlan): Plan = {
        plan.scrutinee = apply(plan.scrutinee)
        plan.onSuccess = apply(plan.onSuccess)
        plan.onFailure = apply(plan.onFailure)
        plan
      }
      def apply(plan: LetPlan): Plan = {
        plan.body = apply(plan.body)
        initializer(plan.sym) = apply(initializer(plan.sym))
        plan
      }
      def apply(plan: LabelledPlan): Plan = {
        plan.body = apply(plan.body)
        labelled(plan.sym) = apply(labelled(plan.sym))
        plan
      }
      def apply(plan: CallPlan): Plan = plan
      def apply(plan: Plan): Plan = plan match {
        case plan: TestPlan => apply(plan)
        case plan: LetPlan => apply(plan)
        case plan: LabelledPlan => apply(plan)
        case plan: CallPlan => apply(plan)
        case plan: CodePlan => plan
      }
    }

    private class RefCounter extends PlanTransform {
      val count = new mutable.HashMap[Symbol, Int] {
        override def default(key: Symbol) = 0
      }
    }

    /** Reference counts for all labels */
    private def labelRefCount(plan: Plan): collection.Map[Symbol, Int] = {
      object refCounter extends RefCounter {
        override def apply(plan: LabelledPlan): Plan = {
          apply(plan.body)
          if (count(plan.sym) != 0) apply(labelled(plan.sym))
          plan
        }
        override def apply(plan: CallPlan): Plan = {
          count(plan.label) += 1
          plan
        }
      }
      refCounter(plan)
      refCounter.count
    }

    /** Reference counts for all variables */
    private def varRefCount(plan: Plan): collection.Map[Symbol, Int] = {
      object refCounter extends RefCounter {
        override val treeMap = new TreeMap {
          override def transform(tree: Tree)(implicit ctx: Context) = tree match {
            case tree: Ident =>
              if (isPatmatGenerated(tree.symbol)) count(tree.symbol) += 1
              tree
            case _ =>
              super.transform(tree)
          }
        }
        override def apply(plan: LetPlan): Plan = {
          apply(plan.body)
          if (count(plan.sym) != 0 || !isPatmatGenerated(plan.sym))
            apply(initializer(plan.sym))
          plan
        }
        override def apply(plan: LabelledPlan): Plan = {
          apply(labelled(plan.sym))
          apply(plan.body)
          plan
        }
        override def apply(plan: CallPlan): Plan = {
          for ((formal, actual) <- plan.args)
            if (count(formal) != 0) count(actual) += 1
          plan
        }
      }
      refCounter(plan)
      refCounter.count
    }

    /** Rewrite everywhere
     *
     *     if C then (let L = B in E1) else E2
     * -->
     *     let L = B in if C then E1 else E2
     *
     *     if C then E1 else (let L = B in E2)
     * -->
     *     let L = B in if C then E1 else E2
     *
     *     let L1 = (let L2 = B2 in B1) in E
     * -->
     *     let L2 = B2 in let L1 = B1 in E
    */
    object hoistLabels extends PlanTransform {
      override def apply(plan: TestPlan): Plan =
        plan.onSuccess match {
          case lp @ LabelledPlan(sym, body, _) =>
            plan.onSuccess = body
            lp.body = plan
            apply(lp)
          case _ =>
            plan.onFailure match {
              case lp @ LabelledPlan(sym, body, _) =>
                plan.onFailure = body
                lp.body = plan
                apply(lp)
              case _ =>
                super.apply(plan)
            }
        }
      override def apply(plan: LabelledPlan): Plan =
        labelled(plan.sym) match {
          case plan1: LabelledPlan =>
            labelled(plan.sym) = plan1.body
            plan1.body = plan
            apply(plan1)
          case _ =>
            super.apply(plan)
        }
    }

    /** Eliminate tests that are redundant (known to be true or false).
     *  Two parts:
     *
     *   - If we know at some point that a test is true or false skip it and continue
     *     diretcly with the test's onSuccess or onFailure continuation.
     *   - If a label of a call points to a test that is known to be true or false
     *     at the point of call, let the label point instead to the test's onSuccess
     *     or onFailure continuation.
     *
     *  We use some tricks to identify a let pointing to an unapply and the
     *  NonEmptyTest that follows it as a single `UnappTest` test.
     */
    def elimRedundantTests(plan: Plan): Plan = {
      type SeenTests = Map[TestPlan, Boolean] // Map from tests to their outcomes

      def isUnapply(sym: Symbol) = sym.name == nme.unapply || sym.name == nme.unapplySeq

      /** A locally used test value that represents combos of
       *
       *   let x = X.unapply(...) in if !x.isEmpty then ... else ...
       */
      case object UnappTest extends Test

      /** If `plan` is the NonEmptyTest part of an unapply, the corresponding UnappTest
       *  otherwise the original plan
       */
      def normalize(plan: TestPlan): TestPlan = plan.scrutinee match {
        case id: Ident
        if plan.test == NonEmptyTest &&
           isPatmatGenerated(id.symbol) &&
           isUnapply(initializer(id.symbol).symbol) =>
          TestPlan(UnappTest, initializer(id.symbol), plan.pos, plan.onSuccess, plan.onFailure)
        case _ =>
          plan
      }

      /** Extractor for Let/NonEmptyTest combos that represent unapplies */
      object UnappTestPlan {
        def unapply(plan: Plan): Option[TestPlan] = plan match {
          case LetPlan(sym, body: TestPlan) =>
            val RHS = initializer(sym)
            normalize(body) match {
              case normPlan @ TestPlan(UnappTest, RHS, _, _, _) => Some(normPlan)
              case _ => None
            }
          case _ => None
        }
      }

      def intersect(tests1: SeenTests, tests2: SeenTests) =
        tests1.filter { case(test, outcome) => tests2.get(test) == Some(outcome) }

      /** The tests with known outcomes valid at entry to label */
      val seenAtLabel = mutable.HashMap[Symbol, SeenTests]()

      class ElimRedundant(seenTests: SeenTests) extends PlanTransform {
        override def apply(plan: TestPlan): Plan = {
          val normPlan = normalize(plan)
          seenTests.get(normPlan) match {
            case Some(outcome) =>
              apply(if (outcome) plan.onSuccess else plan.onFailure)
            case None =>
              plan.onSuccess = new ElimRedundant(seenTests + (normPlan -> true))(plan.onSuccess)
              plan.onFailure = new ElimRedundant(seenTests + (normPlan -> false))(plan.onFailure)
              plan
          }
        }
        override def apply(plan: LabelledPlan): Plan = {
          plan.body = apply(plan.body)
          for (seenTests1 <- seenAtLabel.get(plan.sym))
            labelled(plan.sym) = new ElimRedundant(seenTests1)(labelled(plan.sym))
          plan
        }
        override def apply(plan: CallPlan): Plan = {
          val label = plan.label
          def redirect(target: Plan): Plan = {
            def forward(tst: TestPlan) = seenTests.get(tst) match {
              case Some(true) => redirect(tst.onSuccess)
              case Some(false) => redirect(tst.onFailure)
              case none => target
            }
            target match {
              case tst: TestPlan => forward(tst)
              case UnappTestPlan(tst) => forward(tst)
              case _ => target
            }
          }
          redirect(labelled(label)) match {
            case target: CallPlan =>
              apply(target)
            case _ =>
              seenAtLabel(label) = seenAtLabel.get(label) match {
                case Some(seenTests1) => intersect(seenTests1, seenTests)
                case none => seenTests
              }
              plan
          }
        }
      }
      new ElimRedundant(Map())(plan)
    }

    /** Inline labelled blocks that are referenced only once.
     *  Drop all labels that are not referenced anymore after this.
     */
    private def inlineLabelled(plan: Plan) = {
      val refCount = labelRefCount(plan)
      def toDrop(sym: Symbol) = labelled.contains(sym) && refCount(sym) <= 1
      class Inliner extends PlanTransform {
        override def apply(plan: LabelledPlan): Plan =
          if (toDrop(plan.sym)) apply(plan.body) else super.apply(plan)
        override def apply(plan: CallPlan): Plan = {
          if (refCount(plan.label) == 1) apply(labelled(plan.label))
          else plan
        }
      }
      (new Inliner)(plan)
    }

    /** Merge variables that have the same right hand side.
     *  Propagate common variable bindings as parameters into case labels.
     */
    private def mergeVars(plan: Plan): Plan = {
      class RHS(val tree: Tree) {
        override def equals(that: Any) = that match {
          case that: RHS => this.tree === that.tree
          case _ => false
        }
        override def hashCode: Int = tree.hash
      }
      type SeenVars = Map[RHS, TermSymbol]

      /** The variables known at entry to label */
      val seenAtLabel = mutable.HashMap[Symbol, SeenVars]()

      /** Parameters of label; these are passed additional variables
       *  which are known at all callsites.
       */
      val paramsOfLabel = mutable.HashMap[Symbol, SeenVars]()

      class Merge(seenVars: SeenVars) extends PlanTransform {
        override val treeMap = new TreeMap {
          override def transform(tree: Tree)(implicit ctx: Context) = tree match {
            case tree: Ident =>
              val sym = tree.symbol
              initializer.get(sym) match {
                case Some(id: Ident @unchecked)
                if isPatmatGenerated(sym) && isPatmatGenerated(id.symbol) =>
                  transform(id)
                case none => tree
              }
            case _ =>
              super.transform(tree)
          }
        }

        override def apply(plan: LetPlan): Plan = {
          initializer(plan.sym) = apply(initializer(plan.sym))
          val seenVars1 =
            if (isPatmatGenerated(plan.sym)) {
              val thisRhs = new RHS(initializer(plan.sym))
              seenVars.get(thisRhs) match {
                case Some(seen) =>
                  initializer(plan.sym) = ref(seen)
                  seenVars
                case none =>
                  seenVars.updated(thisRhs, plan.sym)
              }
            }
            else seenVars
          plan.body = new Merge(seenVars1)(plan.body)
          plan
        }

        override def apply(plan: LabelledPlan): Plan = {
          seenAtLabel(plan.sym) = seenVars
          plan.body = apply(plan.body)
          val paramsMap = paramsOfLabel.getOrElse(plan.sym, Map())
          plan.params = paramsMap.values.toList.sortBy(_.name.toString)
          val seenVars1 = seenVars ++ paramsMap
          labelled(plan.sym) = new Merge(seenVars1)(labelled(plan.sym))
          plan
        }

        override def apply(plan: CallPlan): Plan = {
          paramsOfLabel(plan.label) = paramsOfLabel.get(plan.label) match {
            case Some(params) =>
              params.filter { case (rhs, _) => seenVars.contains(rhs) }
            case none =>
              for ((rhs, _) <- seenVars if !seenAtLabel(plan.label).contains(rhs))
              yield (rhs, newVar(rhs.tree, Param))
          }
          plan.args =
            for {
              (rhs, actual) <- seenVars.toList
              formal <- paramsOfLabel(plan.label).get(rhs)
            }
            yield (formal -> actual)
          plan
        }
      }
      (new Merge(Map()))(plan)
    }

    /** Inline let-bound trees that are referenced only once.
     *  Drop all variables that are not referenced anymore after this.
     */
    private def inlineVars(plan: Plan): Plan = {
      val refCount = varRefCount(plan)
      val LetPlan(topSym, _) = plan

      def toDrop(sym: Symbol) = initializer.get(sym) match {
        case Some(rhs) =>
          isPatmatGenerated(sym) && refCount(sym) <= 1 && sym != topSym && isPureExpr(rhs)
        case none =>
          false
      }

      object Inliner extends PlanTransform {
        override val treeMap = new TreeMap {
          override def transform(tree: Tree)(implicit ctx: Context) = tree match {
            case tree: Ident =>
              val sym = tree.symbol
              if (toDrop(sym)) transform(initializer(sym))
              else tree
            case _ =>
              super.transform(tree)
          }
        }
        override def apply(plan: LetPlan): Plan = {
          if (toDrop(plan.sym)) apply(plan.body)
          else {
            initializer(plan.sym) = apply(initializer(plan.sym))
            plan.body = apply(plan.body)
            plan
          }
        }
        override def apply(plan: LabelledPlan): Plan = {
          plan.params = plan.params.filter(refCount(_) != 0)
          super.apply(plan)
        }
        override def apply(plan: CallPlan): Plan = {
          plan.args = plan.args
            .filter(formalActual => refCount(formalActual._1) != 0)
            .sortBy(_._1.name.toString)
          plan
        }
      }
      Inliner(plan)
    }

    // ----- Generating trees from plans ---------------

    /** The condition a test plan rewrites to */
    private def emitCondition(plan: TestPlan): Tree = {
      val scrutinee = plan.scrutinee
      (plan.test: @unchecked) match {
        case NonEmptyTest =>
          scrutinee
            .select(nme.isEmpty, _.info.isParameterless)
            .select(nme.UNARY_!, _.info.isParameterless)
        case NonNullTest =>
          scrutinee.testNotNull
        case GuardTest =>
          scrutinee
        case EqualTest(tree) =>
          tree.equal(scrutinee)
        case LengthTest(len, exact) =>
          scrutinee
            .select(defn.Seq_lengthCompare.matchingMember(scrutinee.tpe))
            .appliedTo(Literal(Constant(len)))
            .select(if (exact) defn.Int_== else defn.Int_>=)
            .appliedTo(Literal(Constant(0)))
        case TypeTest(tpt) =>
          val expectedTp = tpt.tpe

          // An outer test is needed in a situation like  `case x: y.Inner => ...`
          def outerTestNeeded: Boolean = {
            // See the test for SI-7214 for motivation for dealias. Later `treeCondStrategy#outerTest`
            // generates an outer test based on `patType.prefix` with automatically dealises.
            expectedTp.dealias match {
              case tref @ TypeRef(pre: SingletonType, _) =>
                tref.symbol.isClass &&
                ExplicitOuter.needsOuterIfReferenced(tref.symbol.asClass)
              case _ =>
                false
            }
          }

          def outerTest: Tree = trans.transformFollowingDeep {
            val expectedOuter = singleton(expectedTp.normalizedPrefix)
            val expectedClass = expectedTp.dealias.classSymbol.asClass
            ExplicitOuter.ensureOuterAccessors(expectedClass)(ctx.withPhase(ctx.explicitOuterPhase.next))
            scrutinee.ensureConforms(expectedTp)
              .outerSelect(1, expectedOuter.tpe.widen)
              .select(defn.Object_eq)
              .appliedTo(expectedOuter)
          }

          expectedTp.dealias match {
            case expectedTp: SingletonType =>
              scrutinee.isInstance(expectedTp)  // will be translated to an equality test
            case _ =>
              val typeTest = scrutinee.select(defn.Any_typeTest).appliedToType(expectedTp)
              if (outerTestNeeded) typeTest.and(outerTest) else typeTest
          }
      }
    }

    /** Collect longest list of plans that represent possible cases of
     *  a switch, including a last default case, by starting with this
     *  plan and following onSuccess plans.
     */
    private def collectSwitchCases(plan: TestPlan): List[Plan] = {
      def isSwitchableType(tpe: Type): Boolean =
        (tpe isRef defn.IntClass) ||
        (tpe isRef defn.ByteClass) ||
        (tpe isRef defn.ShortClass) ||
        (tpe isRef defn.CharClass)

      val scrutinee = plan.scrutinee

      def isIntConst(tree: Tree) = tree match {
        case Literal(const) => const.isIntRange
        case _ => false
      }

      def recur(plan: Plan): List[Plan] = plan match {
        case TestPlan(EqualTest(tree), scrut, _, _, onf)
        if scrut === scrutinee && isIntConst(tree) =>
          plan :: recur(onf)
        case _ =>
          plan :: Nil
      }

      recur(plan)
    }

    /** Emit cases of a switch */
    private def emitSwitchCases(cases: List[Plan]): List[CaseDef] = (cases: @unchecked) match {
      case TestPlan(EqualTest(tree), _, _, ons, _) :: cases1 =>
        CaseDef(tree, EmptyTree, emit(ons)) :: emitSwitchCases(cases1)
      case (default: Plan) :: Nil =>
        CaseDef(Underscore(defn.IntType), EmptyTree, emit(default)) :: Nil
    }

    /** If selfCheck is `true`, used to check whether a tree gets generated twice */
    private val emitted = mutable.Set[Int]()

    /** Translate plan to tree */
    private def emit(plan: Plan): Tree = {
      if (selfCheck) {
        assert(plan.isInstanceOf[CallPlan] || !emitted.contains(plan.id), plan.id)
        emitted += plan.id
      }
      plan match {
        case plan: TestPlan =>
          val switchCases = collectSwitchCases(plan)
          if (switchCases.lengthCompare(4) >= 0) // at least 3 cases + default
            Match(plan.scrutinee, emitSwitchCases(switchCases))
          else
            If(emitCondition(plan).withPos(plan.pos), emit(plan.onSuccess), emit(plan.onFailure))
        case LetPlan(sym, body) =>
          seq(ValDef(sym, initializer(sym).ensureConforms(sym.info)) :: Nil, emit(body))
        case LabelledPlan(label, body, params) =>
          label.info = MethodType.fromSymbols(params, resultType)
          val labelDef = DefDef(label, Nil, params :: Nil, resultType, emit(labelled(label)))
          seq(labelDef :: Nil, emit(body))
        case CodePlan(tree) =>
          tree
        case CallPlan(label, args) =>
          ref(label).appliedToArgs(args.map { case (_, actual) => ref(actual) })
      }
    }

    /** Pretty-print plan; used for debugging */
    def show(plan: Plan): String = {
      val lrefCount = labelRefCount(plan)
      val vrefCount = varRefCount(plan)
      val sb = new StringBuilder
      val seen = mutable.Set[Int]()
      def showTest(test: Test) = test match {
        case EqualTest(tree) => i"EqualTest($tree)"
        case TypeTest(tpt) => i"TypeTest($tpt)"
        case _ => test.toString
      }
      def showPlan(plan: Plan): Unit =
        if (!seen.contains(plan.id)) {
          seen += plan.id
          sb append s"\n${plan.id}: "
          plan match {
            case TestPlan(test, scrutinee, _, ons, onf) =>
              sb.append(i"$scrutinee ? ${showTest(test)}(${ons.id}, ${onf.id})")
              showPlan(ons)
              showPlan(onf)
            case LetPlan(sym, body) =>
              sb.append(i"Let($sym = ${initializer(sym)}}, ${body.id})")
              sb.append(s", refcount = ${vrefCount(sym)}")
              showPlan(body)
            case LabelledPlan(label, body, params) =>
              val labeld = labelled(label)
              def showParam(param: Symbol) =
                i"$param: ${param.info}, refCount = ${vrefCount(param)}"
              sb.append(i"Labelled($label(${params.map(showParam)}%, %) = ${labeld.id}, ${body.id})")
              sb.append(s", refcount = ${lrefCount(label)}")
              showPlan(body)
              showPlan(labeld)
            case CodePlan(tree) =>
              sb.append(tree.show)
            case CallPlan(label, params) =>
              sb.append(s"Call($label(${params.map(_._2)}%, %)")
          }
        }
      showPlan(plan)
      sb.toString
    }

    /** If match is switch annotated, check that it translates to a switch
     *  with at least as many cases as the original match.
     */
    private def checkSwitch(original: Match, result: Tree) = original.selector match {
      case Typed(_, tpt) if tpt.tpe.hasAnnotation(defn.SwitchAnnot) =>
        val resultCases = result match {
          case Match(_, cases) => cases
          case Block(_, Match(_, cases)) => cases
          case _ => Nil
        }
        def numConsts(cdefs: List[CaseDef]): Int = {
          val tpes = cdefs.map(_.pat.tpe)
          tpes.toSet.size: Int // without the type ascription, testPickling fails because of #2840.
        }
        if (numConsts(resultCases) < numConsts(original.cases))
          ctx.warning(i"could not emit switch for @switch annotated match", original.pos)
      case _ =>
    }

    val optimizations: List[(String, Plan => Plan)] = List(
      "hoistLabels" -> hoistLabels,
      "elimRedundantTests" -> elimRedundantTests,
      "inlineLabelled" -> inlineLabelled,
      "mergeVars" -> mergeVars,
      "inlineVars" -> inlineVars
    )

    /** Translate pattern match to sequence of tests. */
    def translateMatch(tree: Match): Tree = {
      var plan = matchPlan(tree)
      patmatch.println(i"Plan for $tree: ${show(plan)}")
      if (!ctx.settings.YnoPatmatOpt.value)
        for ((title, optimization) <- optimizations) {
          plan = optimization(plan)
          patmatch.println(s"After $title: ${show(plan)}")
        }
      val result = emit(plan)
      checkSwitch(tree, result)
      result
    }
  }
}
