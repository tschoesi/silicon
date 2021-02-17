package rpi.inference.teacher.state

import rpi.inference.context.{Instance, Specification}
import rpi.inference.Abstraction
import rpi.util.{Collections, SetMap}
import viper.silver.ast

/**
  * A state snapshot for the given instance.
  *
  * @param instance The specification instance.
  * @param state    The state.
  */
case class Snapshot(instance: Instance, state: StateEvaluator) {
  // lazily computed reachability map used to adapt expressions
  private[state] lazy val reachability = {
    /**
      * Helper method that updates the current reachability map by recursing the given number of steps.
      *
      * @param current The current reachability map.
      * @param steps   The number of steps.
      * @return The reachability map.
      */
    def recurse(current: Map[String, Set[ast.Exp]], steps: Int): Map[String, Set[ast.Exp]] =
      if (steps == 0) current
      else {
        // compute next step of reachability
        val next = current.foldLeft(Map.empty[String, Set[ast.Exp]]) {
          case (map1, (key, paths)) => state
            .heap
            .getOrElse(key, Map.empty)
            .foldLeft(map1) {
              case (map2, (name, value)) =>
                val field = ast.Field(name, ast.Ref)()
                val extended = paths.map { path => ast.FieldAccess(path, field)(): ast.Exp }
                SetMap.addAll(map2, value, extended)
            }
        }
        // recurse and combine results
        val future = recurse(next, steps - 1)
        SetMap.union(current, future)
      }

    // initial reachability map.
    val initial = instance
      .arguments
      .zip(instance.specification.variables)
      .foldLeft(Map.empty[String, Set[ast.Exp]]) {
        case (result, (argument, parameter)) =>
          if (argument.typ == ast.Ref) {
            val value = state.evaluateReference(argument)
            SetMap.add(result, value, parameter)
          } else result
      }

    // recursively follow fields
    // TODO: Number of steps.
    recurse(initial, steps = 3)
  }

  // reachability map with null literal
  private[state] lazy val nullableReachability = {
    val nil = ast.NullLit()()
    val value = state.evaluateReference(nil)
    SetMap.add(reachability, value, nil)
  }

  // lazily computed abstraction
  private lazy val abstraction: Abstraction = {
    val values = instance
      .formalAtoms
      .zipWithIndex
      .map { case (atom, index) =>
        val name = s"${label}_$index"
        val value = state.evaluateBoolean(name)
        atom -> value
      }
      .toMap
    Abstraction(values)
  }

  def label: String = state.label.get

  def specification: Specification = instance.specification

  /**
    * Returns the state abstraction in terms of the formal parameters.
    *
    * @return The formal state abstraction.
    */
  def formalAbstraction: Abstraction = abstraction

  /**
    * Returns the state abstraction in terms of the actual arguments.
    *
    * @return The actual state abstraction.
    */
  def actualAbstraction: Abstraction = instance.toActual(abstraction)


}

/**
  * A helper class used to adapt expressions from one state to another.
  *
  * @param source The source state.
  * @param target The target state.
  */
case class Adaptor(source: StateEvaluator, target: Snapshot) {
  /**
    * Adapts the given location to the target state.
    *
    * @param location The location to adapt.
    * @return The adapted location.
    */
  def adaptLocation(location: ast.LocationAccess): Set[ast.LocationAccess] =
    location match {
      case ast.FieldAccess(receiver, field) =>
        val adaptedReceiver = adaptReference(receiver, nonnull = true)
        adaptedReceiver.map { adapted => ast.FieldAccess(adapted, field)() }
      case ast.PredicateAccess(arguments, name) =>
        val adaptedArguments = arguments.map { argument => adaptReference(argument) }
        Collections
          .product(adaptedArguments)
          .map { combination => ast.PredicateAccess(combination, name)() }
    }

  /**
    * Adapts the given state abstraction to the target state.
    *
    * @param abstraction The state  abstraction to adapt.
    * @return The adapted state abstraction.
    */
  def adaptAbstraction(abstraction: Abstraction): Abstraction = {
    val values = abstraction
      .values
      .flatMap { case (atom, value) =>
        val adaptedAtom = adapt(atom)
        adaptedAtom.map { adapted => adapted -> value }
      }
    Abstraction(values)
  }

  private def adapt(expression: ast.Exp): Set[ast.Exp] =
    if (expression.typ == ast.Ref) {
      adaptReference(expression)
    } else expression match {
      case ast.EqCmp(left, right) =>
        for (l <- adapt(left); r <- adapt(right)) yield ast.EqCmp(l, r)()
      case ast.NeCmp(left, right) =>
        for (l <- adapt(left); r <- adapt(right)) yield ast.NeCmp(l, r)()
    }

  private def adaptReference(expression: ast.Exp, nonnull: Boolean = false): Set[ast.Exp] = {
    val value = source.evaluateReference(expression)
    if (nonnull) target.reachability.getOrElse(value, Set.empty)
    else target.nullableReachability.getOrElse(value, Set.empty)

  }
}