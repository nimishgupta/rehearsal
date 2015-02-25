package pipeline

import puppet.syntax._
import puppet.graph._

import puppet.common.{resource => resrc}

import scalax.collection.Graph
import scalax.collection.GraphEdge._
import scalax.collection.GraphPredef._
import scalax.collection.edge.Implicits._

import fsmodel._
import fsmodel.ext._

package object pipeline {

  import scalax.collection.mutable.{Graph => MGraph}

  def run(mainFile: String, modulePath: Option[String] = None) {
    runProgram(load(mainFile, modulePath))
  }

  def runProgram(program: String): Int = {

    import fsmodel.core._

    val graph = parse(program).desugar()
                              .toGraph(Facter.run())

    implicit val toExpr = {(r: puppet.graph.Resource) => Provider(toCoreResource(r)).toFSOps()}
    
    val mgraph = MGraph.from(graph.nodes.map(_.value),
                             graph.edges.map((e) => e.source.value ~> e.target.value))

    val ext_expr = toFSExpr(mgraph)
    
    // TODO(nimish): debug only
    val simple_expr = ext_expr.unconcur()
                              .unatomic()

    val opt_expr = ext_expr.unconcurOpt()
                           .unatomic()

    val init_state = Map(paths.root -> IsDir)
    val states = Eval.eval(opt_expr.toCore(), init_state)

    // TODO(nimish): debug only
    if(states.size != 1) {
      printDOTGraph(graph)
      println()
      println(ext_expr.pretty())
      println()
      println(simple_expr.pretty())   
      println()
      println(opt_expr.pretty())
      println()
      println()
      println()
    }
      
    states.size
  }

  // TODO: tail recursive
  // Reduce the graph to a single expression in fsmodel language
  def toFSExpr[A](graph: MGraph[A, DiEdge])
                 (implicit toExpr: A=>ext.Expr): ext.Expr = {
    
    import fsmodel.ext.Implicits._
    
    if(graph.isEmpty) Skip
    else {
      val roots = graph.nodes.filter(_.inDegree == 0)
      roots.map((n) => Atomic(toExpr(n.value))).reduce[ext.Expr](_ * _) >> toFSExpr(graph -- roots)
    }
  }

  def toCoreValue(v: Value): resrc.Value = v match {
    case UndefV => resrc.UndefV
    case StringV(s) => resrc.StringV(s)
    case BoolV(b) => resrc.BoolV(b)
    case RegexV(_) => resrc.UndefV
    case ASTHashV(_) => resrc.UndefV
    case ASTArrayV(arr) => resrc.ArrayV(arr.map(toCoreValue(_))) 
    case ResourceRefV(_, _, _) => resrc.UndefV
  }

  def toCoreResource(res: puppet.graph.Resource): resrc.Resource = {
    // Rules to convert to core resource
    val attrs = res.as.filter((a) => a.value match {
      case UndefV | StringV(_) | BoolV(_) | ASTArrayV(_) => true
      case _ => false
    })
    .map((a) => (a.name, toCoreValue(a.value))).toMap

    resrc.Resource(attrs)
  }
}
