import java.io.FileReader 


object main {

  def main (args: Array[String]) {

    // val reader = new FileReader (args (0))
    println (PuppetParser (scala.io.Source.fromFile (args (0)).mkString))
  }
}
