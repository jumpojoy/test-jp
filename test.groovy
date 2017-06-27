def common = new com.mirantis.mk.Common()
def git = new com.mirantis.mk.Git()
def ssh = new com.mirantis.mk.Ssh()

timestamps {
  node("master"){
    ssh.prepareSshAgentKey(CREDENTIALS_ID)
    ssh.ensureKnownHosts(GERRIT_HOST)
    stage("checkout") {
      wrap([$class: 'AnsiColorBuildWrapper']) {
        ssh.agentSh(String.format("ssh %s@%s 'git clone https://github.com/jumpojoy/mcp-underlay-aio /root/mcp-underlay-aio'", GERRIT_NAME, GERRIT_HOST))
        common.successMsg("Successfully clone https://github.com/jumpojoy/mcp-underlay-aio")
      }
    }
    stage("install_salt") {
        wrap([$class: 'AnsiColorBuildWrapper']) {
        ssh.agentSh(String.format("ssh %s@%s 'bash /root/mcp-underlay-aio/scripts/aio-setup.sh'", GERRIT_NAME, GERRIT_HOST))
        common.successMsg("Salt has been installed successfully.")
      }
    }
    sh("id")
  }
}
