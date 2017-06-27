def common = new com.mirantis.mk.Common()
def git = new com.mirantis.mk.Git()
def ssh = new com.mirantis.mk.Ssh()

timestamps {
  node("master"){
    ssh.prepareSshAgentKey(CREDENTIALS_ID)
    ssh.ensureKnownHosts(GERRIT_HOST)
    ssh.agentSh(String.format("ssh %s@%s id", GERRIT_NAME, GERRIT_HOST))
    sh("id")
  }
}
