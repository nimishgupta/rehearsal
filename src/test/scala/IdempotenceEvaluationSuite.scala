class IdempotenceEvaluationSuite extends org.scalatest.FunSuite {

  import rehearsal._
  import PuppetParser.parseFile

  import FSSyntax._
  import scalax.collection.Graph
  import scalax.collection.GraphEdge.DiEdge
  import rehearsal.Implicits._
  import java.nio.file.Paths
  import SymbolicEvaluator.{predEquals, exprEquals, isIdempotent}
  import PuppetSyntax._

  val root = "parser-tests/good"

  test("dhoppe-monit.pp") {
    val g = parseFile(s"$root/dhoppe-monit.pp").eval.resourceGraph.fsGraph("ubuntu-trusty")
    assert(SymbolicEvaluator.isIdempotent(g) == true)
  }

  test("dhoppe-monit_BUG.pp") {
    val g = parseFile(s"$root/dhoppe-monit_BUG.pp").eval.resourceGraph.fsGraph("ubuntu-trusty")
    assert(SymbolicEvaluator.isIdempotent(g) == true)
  }

  test("thias-bind.pp") {
    val g = parseFile(s"$root/thias-bind.pp").eval.resourceGraph.fsGraph("ubuntu-trusty")
    assert(SymbolicEvaluator.isIdempotent(g) == true)
  }

  test("pdurbin-java-jpa-tutorial.pp") {
    val g = parseFile(s"$root/pdurbin-java-jpa-tutorial.pp").eval.resourceGraph.fsGraph("centos-6")
    assert(SymbolicEvaluator.isIdempotent(g) == true)
  }

  test("BenoitCattie-puppet-nginx.pp") {
    val g = parseFile(s"$root/BenoitCattie-puppet-nginx.pp").eval.resourceGraph.fsGraph("ubuntu-trusty")
    assert(SymbolicEvaluator.isIdempotent(g) == true)
  }

}