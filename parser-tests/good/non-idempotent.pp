file{"/vagrant": 
	before => File["/vagrant/dst.txt"],
	ensure => directory
}

file{"/vagrant/dst.txt":
  ensure => file,
  source => "/vagrant/src.txt"
}

file{"/vagrant/src.txt":
  ensure => absent,
  require => File["/vagrant/dst.txt"]
}