
import org.scalatest.{FunSuite, Matchers}

class Core extends FunSuite with Matchers {
  import z3.scala._
  import z3.scala.dsl._

  test("Core") {
    val z3 = new Z3Context(new Z3Config("MODEL" -> true))

    val x = z3.mkFreshConst("x", z3.mkIntSort)
    val y = z3.mkFreshConst("y", z3.mkIntSort)

    val solver = z3.mkSolver
    val expr = (x === y) || (x !== y)
    solver.assertCnstr(expr.ast(z3))
    println(solver.checkAssumptions())
    // solver.assertCnstr(p2 --> !(y === zero))
    // solver.assertCnstr(p3 --> !(x === zero))

    // solver.checkAssumptions(p1, p2, p3) match {

    // result should equal (Some(false))
    // core.toSet should equal (Set(p1, p3))
  }
}

