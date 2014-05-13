package puppet.core.eval

import scala.util.matching.Regex

sealed abstract trait PuppetValue

object PuppetCompositeValueTypes {

  type ValueHashMap = Map[PuppetValue, PuppetValue]
  type ValueArray   = Array[PuppetValue]
}

import PuppetCompositeValueTypes._

case object UndefV extends PuppetValue
case class BoolV (value: Boolean) extends PuppetValue
case class StringV (value: String) extends PuppetValue
case class RegexV (value: Regex) extends PuppetValue
case class ASTHashV (value: ValueHashMap) extends PuppetValue
case class ASTArrayV (value: ValueArray) extends PuppetValue

