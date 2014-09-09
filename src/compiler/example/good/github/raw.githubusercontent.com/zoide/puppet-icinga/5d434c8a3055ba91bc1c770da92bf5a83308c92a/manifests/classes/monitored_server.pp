# $Id$
class icinga::monitored::server inherits icinga::monitored::common {
  # define this host for nagios
  icinga::host { $fqdn: hostgroups => "${operatingsystem},${virtual},${architecture}", 
  }
}

class icinga::monitored::server::nrpe (
  $ensure = "present") {
  class { "icinga::monitored::server": }
  $nrpe_service = $operatingsystem ? {
    "FreeBSD" => "nrpe2",
    "Darwin"  => "org.macports.nrpe",
    default   => "nagios-nrpe-server",
  }
  $nrpe_package = $operatingsystem ? {
    "FreeBSD" => "nrpe2",
    "Darwin"  => "nrpe",
    default   => "nagios-nrpe-server",
  }
  $nagios_user = $operatingsystem ? {
    "Darwin" => "daemon",
    default  => "nagios",
  }
  $nagios_group = $operatingsystem ? {
    "Darwin" => "daemon",
    default  => "nagios",
  }
  $nagiosconf = $operatingsystem ? {
    "FreeBSD" => "/usr/local/etc/nagios",
    "Darwin"  => "/opt/local/etc/nrpe",
    default   => "/etc/nagios",
  }
  $nrpebin = $operatingsystem ? {
    "FreeBSD" => "/usr/local/sbin/nrpe2",
    "Darwin"  => "/opt/local/sbin/nrpe",
    default   => "/usr/sbin/nrpe",
  }
  $nagiosplugins = $operatingsystem ? {
    "FreeBSD" => "/usr/local/libexec/nagios",
    "Darwin"  => "/opt/local/libexec/nagios",
    default   => "/usr/lib/nagios/plugins",
  }
  $nrpecfg = $operatingsystem ? {
    "FreeBSD" => "/usr/local/etc/nrpe.cfg",
    "Darwin"  => "/opt/local/etc/nrpe/nrpe.cfg",
    default   => "/etc/nagios/nrpe.cfg",
  }
  $nrpecfg_local = $operatingsystem ? {
    "FreeBSD" => "/usr/local/etc/nrpe_local.cfg",
    "Darwin"  => "/opt/local/etc/nrpe/nrpe_local.cfg",
    default   => "/etc/nagios/nrpe_local.cfg",
  }

  file { "${nrpecfg}": content => template("icinga/nrpe.cfg.erb"), }

  file { "${nrpecfg_local}": }

  if $kernel == "Darwin" {
    Package {
      provider => "darwinport", }

    exec { "restart-nagios-nrpe":
      command  => "launchctl stop org.macports.nrpe && sleep 2 && launchctl stop org.macports.nrpe && launchctl start org.macports.nrpe",
      path     => ["/bin"],
      schedule => "daily",
    }
  }

  package { $nrpe_package: ensure => installed, }

  case $operatingsystem {
    "Debian", "Ubuntu" : {
      package { [
        "nagios-plugins",
        "nagios-plugins-standard",
        "nagios-plugins-basic"]:
        ensure => $ensure,
      }
    }
    "Darwin"           : {
      package { "nagios-plugins": ensure => $ensure, }
    }
  }

  file { "${nagiosconf}/nrpe.d":
    ensure => "directory",
    owner  => $nagios_user,
    group  => $nagios_group,
  }

  exec { "generate-nrpe.cfg":
    command     => "cat ${nagiosconf}/nrpe.d/* >${nagiosconf}/nrpe_local.cfg",
    refreshonly => true,
    subscribe   => File["${nagiosconf}/nrpe.d"],
    notify      => Service[$nrpe_service],
  }

  service { $nrpe_service:
    ensure     => running,
    enable     => true,
    hasrestart => true,
    hasstatus  => false,
    pattern    => '/usr/sbin/nrpe.*',
    require    => Package["${nrpe_package}"],
    subscribe  => [
      File["${nagiosconf}/nrpe.d"],
      File["${nrpecfg}"],
      File["${nrpecfg_local}"]],
  }

  # add apt nrpe service
  icinga::nrpe_plugin { "${fqdn}_check_nfs_stale":
    command_name         => "check_nfs_stale",
    service_description  => "NFS_STALE",
    notification_period  => "workhours",
    notification_options => "w,c,u",
    ensure               => $kernel ? {
      "FreeBSD" => "absent",
      default   => "present"
    }
  }
  $swap_present = $swapsize ? {
    "0.00 kB" => "absent",
    ""        => "absent",
    default   => $presence ? {
      "absent" => "absent",
      default  => "present"
    } }

  icinga::nrpe_service { "${fqdn}_nrpe_swap":
    command_name         => "check_swap",
    command_line         => "${nagiosplugins}/check_swap -w 3% -c 1%",
    service_description  => "SWAP",
    notification_options => "w,c,u",
    servicegroups        => "Harddrives,Memory",
    ensure               => $swap_present
  }

  icinga::nrpe_service { "${fqdn}_check_diskspace":
    command_name          => "check_diskspace",
    command_line          => "${nagiosplugins}/check_disk -l -X devfs -X linprocfs -X devpts -X tmpfs -X usbfs -X procfs -X proc -X sysfs -X iso9660 -X debugfs -X binfmt_misc -X udf -X devtmpfs -X securityfs -X fusectl -w 10% -c 5%",
    service_description   => "DISKSPACE",
    notification_period   => "workhours",
    notification_interval => "1440",
    servicegroups         => "Harddrives",
  }

  icinga::service { "${fqdn}_nrpe_users ":
    service_description   => "LOGGEDIN_USERS",
    check_command         => "check_nrpe_1arg!check_users",
    notifications_enabled => "1",
    notification_period   => "workhours",
    notification_options  => "w,c,u",
  }

  icinga::service { "${fqdn}_nrpe_zombie_processes":
    service_description => "ZOMBIE_PROCS",
    check_command       => "check_nrpe_1arg!check_zombie_procs",
    notification_period => "workhours",
  }

  icinga::nrpe_service { "${fqdn}_nrpe_apt":
    command_name          => "check_apt",
    command_line          => "/usr/lib/nagios/plugins/check_apt",
    service_description   => "APT",
    normal_check_interval => "1440",
    notification_interval => "50400",
    notification_period   => "workhours",
    notification_options  => "w,c",
    servicegroups         => "Packages",
  }
  $processorcount_real = $processorcount ? {
    ""      => 1,
    default => $processorcount,
  }
  $procs_warn = to_int(max(times($processorcount_real, 100), 750))
  $procs_crit = to_int(max(times($processorcount_real, 150), 950))

  icinga::nrpe_service { "${fqdn}_nrpe_processes":
    command_name         => "check_procs",
    command_line         => "${nagiosplugins}/check_procs -w ${procs_warn} -c ${procs_crit}",
    service_description  => "RUNNING_PROCS",
    notification_period  => "workhours",
    notification_options => "w,c,u",
  }

  $crit_one = max(times($processorcount_real, "5.5"), "10")
  $crit_five = max(times($processorcount_real, "5"), "15")
  $crit_fifteen = max(times($processorcount_real, "4.5"), "20")
  $warn_one = max(times($processorcount_real, "3.5"), "8")
  $warn_five = max(times($processorcount_real, "3.0"), "9")
  $warn_fifteen = max(times($processorcount_real, "2.5"), "10")

  icinga::nrpe_service { "${fqdn}_nrpe_load":
    service_description  => "LOAD",
    command_name         => "check_load",
    command_line         => "${nagiosplugins}/check_load -w ${warn_one},${warn_five},${warn_fifteen} -c ${crit_one},${crit_five},${crit_fifteen}",
    notification_options => "w,c,u",
    ensure               => $ensure,
  }
}
