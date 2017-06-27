def common = new com.mirantis.mk.Common()
def git = new com.mirantis.mk.Git()
def ssh = new com.mirantis.mk.Ssh()

timestamps {
  node("master"){
    ssh.prepareSshAgentKey(CREDENTIALS_ID)
    ssh.ensureKnownHosts(GERRIT_HOST)
    res = ssh.agentSh(String.format("ssh  %s@%s echo %s", GERRIT_NAME, GERRIT_HOST, SALT_FORMULAS_IRONIC_BRANCH))
    common.infoMsg(res)
    sh("id")
  }
}

