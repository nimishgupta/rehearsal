package rehearsal

trait Extractor2[T] {
  def apply(from: PuppetSyntax2.Expr): Option[T]
}

object Implicits {

  import FSSyntax._
  import scala.language.implicitConversions
  import java.nio.file.{Path, Paths, Files}

  implicit def stringToPath(str: String): Path = Paths.get(str)

  implicit class RichString(str: String) {

    def toPath = Paths.get(str)
  }

  implicit class RichPred(a: Pred) {

    def &&(b: Pred): Pred = (a, b) match {
      case (True, _) => b
      case (_, True) => a
      case _ => And(a, b)
    }

    def ||(b: Pred): Pred = (a, b) match {
      case (True, _) => True
      case (False, _) => b
      case (_, True) => True
      case (_, False) => a
      case _ => Or(a, b)
    }

    def unary_!(): Pred = a match {
      case Not(True) => False
      case Not(False) => True
      case Not(a) => a
      case _ => Not(a)
    }
  }

  implicit class RichExpr(e1: Expr) {

    def >>(e2: Expr) = (e1, e2) match {
      case (Skip, _) => e2
      case (_, Skip) => e1
      case (Error, _) => Error
      case (_, Error) => Error
      case _ => Seq(e1, e2)
    }
  }

  implicit class RichManifest(m: Syntax.Manifest) {

    import Syntax._

    def >>(other: Manifest) = (m, other) match {
      case (Empty, _) => other
      case (_, Empty) => m
      case _ => Block(m, other)
    }
  }


  implicit def extractString = new Extractor2[String] {
    import PuppetSyntax2._

    def apply(e: Expr) = e match {
      case Str(s) => Some(s)
      case _ => None
    }
  }

  implicit def extractSBool = new Extractor2[Boolean] {
    import PuppetSyntax2._

    def apply(e: Expr) = e match {
      case Str("yes") => Some(true)
      case Str("no") => Some(false)
      case Bool(b) => Some(b)
      case _ => None
    }
  }

  implicit class RichExpr2(e: PuppetSyntax2.Expr) {
    import PuppetSyntax2._

    def value[T](implicit extractor: Extractor2[T]) = extractor(e)

  }

}
