package dotty.tools
package dotc
package transform
package init

import scala.collection.mutable

import ast.tpd._
import reporting.trace
import config.Printers.init

import core._
import Types._, Symbols._, Contexts._

import Effects._, Summary._

object Potentials {
  type Potentials = Set[Potential]
  val empty: Potentials = Set.empty

  def show(pots: Potentials)(using Context): String =
    pots.map(_.show).mkString(", ")

  /** A potential represents an aliasing of a value that is possibly under initialization */
  sealed trait Potential {
    /** Length of the potential. Used for widening */
    def size: Int = 1

    /** Nested levels of the potential. Used for widening */
    def level: Int = 1

    def show(using Context): String
    def source: Tree
  }

  /** The object pointed by `this` */
  case class ThisRef()(val source: Tree) extends Potential {
    def show(using Context): String = "this"

    /** Effects of a method call or a lazy val access
     */
    def effectsOf(sym: Symbol)(implicit env: Env): Effects = trace("effects of " + sym.show, init, r => Effects.show(r.asInstanceOf)) {
      val cls = sym.owner.asClass
      env.summaryOf(cls).effectsOf(sym)
    }

    /** Potentials of a field, a method call or a lazy val access
     */
    def potentialsOf(sym: Symbol)(implicit env: Env): Potentials = trace("potentials of " + sym.show, init, r => Potentials.show(r.asInstanceOf)) {
      val cls = sym.owner.asClass
      env.summaryOf(cls).potentialsOf(sym)
    }
  }

  /** The object pointed by `C.super.this`, mainly used for override resolution */
  case class SuperRef(pot: Potential, supercls: ClassSymbol)(val source: Tree) extends Potential {
    override def size: Int = pot.size
    override def level: Int = pot.level
    def show(using Context): String = pot.show + ".super[" + supercls.name.show + "]"
  }

  /** A warm potential represents an object of which all fields are initialized, but it may contain
   *  reference to objects under initialization.
   *
   *  @param classSymbol  The concrete class of the object
   *  @param outer        The potential for `this` of the enclosing class
   */
  case class Warm(classSymbol: ClassSymbol, outer: Potential)(val source: Tree) extends Potential {
    override def level: Int = 1 + outer.level
    def show(using Context): String = "Warm[" + classSymbol.show + ", outer = " + outer.show + "]"

    /** Effects of a method call or a lazy val access
     *
     *  The method performs prefix substitution
     */
    def effectsOf(sym: Symbol)(implicit env: Env): Effects = trace("effects of " + sym.show, init, r => Effects.show(r.asInstanceOf)) {
      val cls = sym.owner.asClass
      val effs = env.summaryOf(cls).effectsOf(sym)
      Effects.asSeenFrom(effs, this)
    }

    /** Potentials of a field, a method call or a lazy val access
     *
     *  The method performs prefix substitution
     */
    def potentialsOf(sym: Symbol)(implicit env: Env): Potentials = trace("potentials of " + sym.show, init, r => Potentials.show(r.asInstanceOf)) {
      val cls = sym.owner.asClass
      val pots = env.summaryOf(cls).potentialsOf(sym)
      Potentials.asSeenFrom(pots, this)
    }

    def resolveOuter(cls: ClassSymbol)(implicit env: Env): Potentials =
      env.resolveOuter(this, cls)
  }

  def resolveOuter(cur: ClassSymbol, outerPots: Potentials, cls: ClassSymbol)(implicit env: Env): Potentials =
  trace("resolveOuter for " + cls.show + ", outer = " + show(outerPots) + ", cur = " + cur.show, init, s => Potentials.show(s.asInstanceOf[Potentials])) {
    if (cur == cls) outerPots
    else {
      val bottomClsSummary = env.summaryOf(cur)
      bottomClsSummary.parentOuter.find((k, v) => k.derivesFrom(cls)) match {
        case Some((parentCls, pots)) =>
          val rebased: Potentials = outerPots.flatMap { Potentials.asSeenFrom(pots, _) }
          resolveOuter(parentCls, rebased, cls)
        case None => unreachable()
      }
    }
  }

  /** The Outer potential for `classSymbol` of the object `pot`
   *
   *  It's only used internally for expansion of potentials.
   *
   *  Note: Usage of `Type.baseType(cls)` may simplify the code.
   *        Current implementation avoids using complex type machinary,
   *        and may be potentially faster.
   */
  case class Outer(pot: Potential, classSymbol: ClassSymbol)(val source: Tree) extends Potential {
    // be lenient with size of outer selection, no worry for non-termination
    override def size: Int = pot.size
    override def level: Int = pot.size
    def show(using Context): String = pot.show + ".outer[" + classSymbol.show + "]"
  }

  /** The object pointed by `this.f` */
  case class FieldReturn(potential: Potential, field: Symbol)(val source: Tree) extends Potential {
    assert(field != NoSymbol)

    override def size: Int = potential.size + 1
    override def level: Int = potential.size
    def show(using Context): String = potential.show + "." + field.name.show
  }

  /** The object returned by `this.m()` */
  case class MethodReturn(potential: Potential, method: Symbol)(val source: Tree) extends Potential {
    assert(method != NoSymbol)

    override def size: Int = potential.size + 1
    override def level: Int = potential.size
    def show(using Context): String = potential.show + "." + method.name.show
  }

  /** The object whose initialization status is unknown */
  case class Cold()(val source: Tree) extends Potential {
    def show(using Context): String = "Cold"
  }

  /** A function when called will produce the `effects` and return the `potentials` */
  case class Fun(potentials: Potentials, effects: Effects)(val source: Tree) extends Potential {
    override def size: Int = 1

    override def level: Int = {
      val max1 = potentials.map(_.level).max
      val max2 = effects.map(_.potential.level).max
      if max1 > max2 then max1 else max2
    }

    def show(using Context): String =
      "Fun[pots = " + potentials.map(_.show).mkString(";") + ", effs = " + effects.map(_.show).mkString(";") + "]"
  }

  // ------------------ operations on potentials ------------------

  extension (pot: Potential) def toPots: Potentials = Potentials.empty + pot

  extension (ps: Potentials) def select (symbol: Symbol, source: Tree)(using Context): Summary =
    ps.foldLeft(Summary.empty) { case ((pots, effs), pot) =>
      // max potential length
      // TODO: it can be specified on a project basis via compiler options
      if (pot.size > 2)
        (pots, effs + Promote(pot)(source))
      else if (symbol.isConstructor)
        (pots + pot, effs + MethodCall(pot, symbol)(source))
      else if (symbol.isOneOf(Flags.Method | Flags.Lazy))
          (
            pots + MethodReturn(pot, symbol)(source),
            effs + MethodCall(pot, symbol)(source)
          )
      else
        (pots + FieldReturn(pot, symbol)(source), effs + FieldAccess(pot, symbol)(source))
    }

  extension (ps: Potentials) def promote(source: Tree): Effects = ps.map(Promote(_)(source))

  def asSeenFrom(pot: Potential, thisValue: Potential)(implicit env: Env): Potential = trace(pot.show + " asSeenFrom " + thisValue.show, init, pot => pot.asInstanceOf[Potential].show) {
    pot match {
      case MethodReturn(pot1, sym) =>
        val pot = asSeenFrom(pot1, thisValue)
        MethodReturn(pot, sym)(pot.source)

      case FieldReturn(pot1, sym) =>
        val pot = asSeenFrom(pot1, thisValue)
        FieldReturn(pot, sym)(pot.source)

      case Outer(pot1, cls) =>
        val pot = asSeenFrom(pot1, thisValue)
        Outer(pot, cls)(pot.source)

      case _: ThisRef =>
        thisValue

      case Fun(pots, effs) =>
        val pots1 = Potentials.asSeenFrom(pots, thisValue)
        val effs1 = Effects.asSeenFrom(effs, thisValue)
        Fun(pots1, effs1)(pot.source)

      case Warm(cls, outer2) =>
        // widening to terminate
        val thisValue2 = thisValue match {
          case Warm(cls, outer) if outer.level > 2 =>
            Warm(cls, Cold()(outer2.source))(thisValue.source)

          case _  =>
            thisValue
        }

        val outer3 = asSeenFrom(outer2, thisValue2)
        Warm(cls, outer3)(pot.source)

      case _: Cold =>
        pot

      case SuperRef(potThis, supercls) =>
        val pot1 = asSeenFrom(potThis, thisValue)
        SuperRef(pot1, supercls)(pot.source)
    }
  }

  def asSeenFrom(pots: Potentials, thisValue: Potential)(implicit env: Env): Potentials =
    pots.map(asSeenFrom(_, thisValue))
}