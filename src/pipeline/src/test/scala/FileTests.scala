package pipeline

import org.scalatest.FunSuite

class FileTestSuite extends FunSuite {

  test("single puppet file resource") {
    val program = """file{"/foo": ensure => present }"""
    assert(1 == pipeline.runProgram(program))
  }

  test("single directory") {
    val program = """file{"/tmp":
                              ensure => directory
                            }"""
    assert(1 == pipeline.runProgram(program))
  }

  test("file inside a directory") {
    val program = """file{"/tmp/foo":
                       ensure => present,
                       require => File['/tmp']
                     }
                     file{"/tmp":
                       ensure => directory
                     }"""
    assert(1 == pipeline.runProgram(program))
  }

  test("single puppet file resource with force") {
    val program = """file{"/foo":
                       ensure => file,
                       force => true
                     }"""
    assert(1 == pipeline.runProgram(program))
  }

  test("delete file resource") {
    val program = """file{"/foo": ensure => absent }"""
    assert(1 == pipeline.runProgram(program))
  }

  test("delete dir with force") {
    val program = """file {"/tmp":
                       ensure => absent,
                       force => true
                     }"""
    assert(1 == pipeline.runProgram(program))
  }

  test("link file") {
    val program = """file{"/foo":
                       ensure => link,
                       target => "/bar"
                     }"""
    assert(1 == pipeline.runProgram(program))
  }

  test("link file force") {
    val program = """file{"/foo":
                       ensure => link,
                       target => "/bar",
                       force => true
                     }"""
    assert(1 == pipeline.runProgram(program))
  }
}