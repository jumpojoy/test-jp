def common = new com.mirantis.mk.Common()
def git = new com.mirantis.mk.Git()

timestamps {
  node("master"){
    sh("id")
  }
}
