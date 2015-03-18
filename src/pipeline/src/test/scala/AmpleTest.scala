package pipeline

// TODO(kgeffen) prune
import puppet.syntax._
import puppet.graph._

import puppet.common.{resource => resrc}

import fsmodel._
import fsmodel.ext._
import fsmodel.core.Eval.State

import org.scalatest.FunSuite
import java.nio.file._

import Implicits._
import fsmodel.core.Implicits._

class AmpleTest extends FunSuite {

  test("Seq(p,q) evaluates fully even if p essentially skip") {
    val a: java.nio.file.Path = "/a"
    val e = Seq(Seq(Skip, Skip), Mkdir(a))
    val g = Ample.makeGraph(Ample.initState, e)
    val finalStates = getFinalStates(g)

    assert(finalStates.size == 1)
    assert(finalStates.forall(s => s.keys.exists(_ == a)))
  }

  def getFinalStates(g: Ample.MyGraph): scala.collection.mutable.Set[State] = {
    g.nodes.filter(n => n.outDegree == 0).map(_.value._1)
  }

  test("single package without attributes") {
    val program = """package{"sl": }"""
    runTest("1.dot", program)
  }


  test("2 package dependent install") {
    val program = """package{"sl": }
                     package{"cmatrix":
                       require => Package['sl']
                     }"""
    runTest("2.dot", program)
  }

  test("2 package install") {
    val program = """package{["cmatrix",
                              "telnet"]: }"""
    runTest("3.dot", program)
  }

  // Takes 13 mins
  test("3 package install") {
    val program = """package{["sl",
                              "cowsay",
                              "cmatrix"]: }"""
    runTest("4.dot", program)
  }

  def runTest(filename: String, program: String) {
    val graph = parse(program).desugar()
                              .toGraph(Map[String, String]())

    val extExpr = pipeline.resourceGraphToExpr(graph)
    // info(extExpr.pretty())
    val evalGraph = Ample.makeGraph(Ample.initState, extExpr)
    info(s"Graph has ${evalGraph.nodes.size} nodes")
    val finalStates = getFinalStates(evalGraph)
    // for (p <- finalStates) {
    //   info(p.toString)
    // }
    //l finalStates = Ample.finalStates(Ample.initState, extExpr)
    info(finalStates.size.toString)
    // for(st <- finalStates) {
    //   info(st.toString)
    // }
    //fo(finalStates.toString)
    // Files.write(Paths.get(filename), Ample.drawGraph(extExpr).getBytes)
  }
}
