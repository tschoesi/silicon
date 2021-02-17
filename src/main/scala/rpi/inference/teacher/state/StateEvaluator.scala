package rpi.inference.teacher.state

import viper.silicon.resources.FieldID
import viper.silicon.state.{BasicChunk, State, terms}
import viper.silicon.state.terms.sorts
import viper.silver.ast

/**
  * A helper class used to evaluate a given state.
  *
  * @param label The label of the state.
  * @param state The state.
  * @param model The model.
  */
case class StateEvaluator(label: Option[String], state: State, model: ModelEvaluator) {
  /**
    * Returns the value associated with the given variable.
    * TODO: Precompute or cache?
    *
    * @param variable The variable to look up.
    * @return The value.
    */
  private def store(variable: ast.LocalVar): String = {
    val adapted = label match {
      case Some(label) =>
        val name = s"${label}_${variable.name}"
        ast.LocalVar(name, variable.typ)()
      case None => variable
    }
    val term = state.g(adapted)
    model.evaluateReference(term)
  }

  // precomputed heap map
  private[state] val heap = label
    .map { name => state.oldHeaps(name) }
    .getOrElse(state.h)
    .values
    .foldLeft(Map.empty[String, Map[String, String]]) {
      case (result, chunk: BasicChunk) if chunk.resourceID == FieldID =>
        val term = chunk.snap
        term.sort match {
          case sorts.Ref =>
            val receiver = model.evaluateReference(chunk.args.head)
            val field = chunk.id.name
            val value = model.evaluateReference(term)
            // update field map
            val fields = result
              .getOrElse(receiver, Map.empty)
              .updated(field, value)
            // update heap
            result.updated(receiver, fields)
          case _ =>
            // do nothing
            result
        }
      case (result, _) =>
        result
    }

  def evaluateBoolean(name: String): Boolean = {
    val variable = ast.LocalVar(name, ast.Bool)()
    val term = state.g(variable)
    model.evaluateBoolean(term)
  }

  def evaluatePermission(name: String): Double = {
    val variable = ast.LocalVar(name, ast.Perm)()
    val term = state.g(variable)
    model.evaluatePermission(term)
  }

  /**
    * Evaluates the given reference-typed expression.
    *
    * @param expression The expression to evaluate.
    * @return The value.
    */
  def evaluateReference(expression: ast.Exp): String =
    expression match {
      case ast.NullLit() =>
        model.evaluateReference(terms.Null())
      case variable: ast.LocalVar =>
        store(variable)
      case ast.FieldAccess(receiver, ast.Field(field, _)) =>
        val receiverValue = evaluateReference(receiver)
        heap(receiverValue)(field)
    }
}
