def common = new com.mirantis.mk.Common()
def git = new com.mirantis.mk.Git()
def ssh = new com.mirantis.mk.Ssh()
def salt = new com.mirantis.mk.Salt()
def orchestrate = new com.mirantis.mk.Orchestrate()


def targetAll = ['expression': '*', 'type': 'compound']
def SALT_MASTER_URL = "http://${GERRIT_HOST}:8000"
SALT_MASTER_CREDENTIALS = 'salt_api'

def get_node_names(master){
    def salt = new com.mirantis.mk.Salt()

    result = salt.runSaltProcessStep(master, 'I@ironic:client and *01*', 'ironicng.list_nodes', ['profile=admin_identity'], null, true)
    node_names = []
    if(result != null){
        if(result['return']){
            for (int i=0;i<result['return'].size();i++) {
                def entry = result['return'][i]
                for (item in entry.keySet()) {
                    def nodes = entry[item]['nodes']
                    for (node in nodes){
                        node_names.add(node['name'])
                    }
                }
            }
        }
    }
    return node_names
}

def wait_deployment_complete(master, nodes){
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()

    nodes_done = []
    while (nodes.size() != nodes_done.size()) {
        for (node_name in nodes){
            if (node_name in nodes_done){
                 continue
            }

            result = salt.runSaltProcessStep(master, 'I@ironic:client and *01*', 'ironicng.show_node', ["node_id=${node_name}", "profile=admin_identity"], null, output=false)
            for (int i=0;i<result['return'].size();i++) {
                entry = result['return'][i]
                for (item in entry.keySet()) {
                    ir_node = entry[item]
                    if (ir_node['provision_state'] == 'active'){
                        common.successMsg("Deployment of node ${node_name} has been finished successfully.")
                        nodes_done.add(node_name)
                    } else if (ir_node['provision_state'] == 'deploy failed'){
                        common.errorMsg("Deployment of node  ${node_name} has been failed.")
                        nodes_done.add(node_name)
                    } else if (ir_node['provision_state'] in ['available']){
                        common.warningMsg("Skipping checking node ${node_name} as deployment wasn't started for this node.")
                        nodes_done.add(node_name)
                    } else {
                        common.infoMsg("Waiting for node  ${node_name} to be deployed. Current state is: ${ir_node['provision_state']}")
                    }
                }
            }
        }
        if (nodes.size() == nodes_done.size()){
            break
        }
        sleep(15)
    }
}

timestamps {
    node("master"){
        def saltMaster

        ssh.prepareSshAgentKey(CREDENTIALS_ID)
        ssh.ensureKnownHosts(GERRIT_HOST)
        stage("checkout") {
            wrap([$class: 'AnsiColorBuildWrapper']) {
                ssh.agentSh(String.format("ssh %s@%s 'git clone https://github.com/jumpojoy/mcp-underlay-aio /root/mcp-underlay-aio'", GERRIT_NAME, GERRIT_HOST))
            }
            common.successMsg("Successfully clone https://github.com/jumpojoy/mcp-underlay-aio")
        }
    stage("install_salt") {
        wrap([$class: 'AnsiColorBuildWrapper']) {
            ssh.agentSh(String.format("ssh %s@%s 'export SALT_FORMULAS_IRONIC_BRANCH=$SALT_FORMULAS_IRONIC_BRANCH; bash /root/mcp-underlay-aio/scripts/aio-setup.sh'", GERRIT_NAME, GERRIT_HOST))
        }
        common.successMsg("Salt has been installed successfully.")
    }

        stage('Connect to Salt master') {
            saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        stage('List target servers') {
            minions = salt.getMinions(saltMaster, targetAll)
            common.infoMsg(minions)
        }

        stage("Install core infra") {

            salt.enforceState(saltMaster, '*', ['linux'], output=true)
            salt.enforceState(saltMaster, '*', ['salt.minion'], output=true, timeout=30, failOnError=false)
            salt.enforceState(saltMaster, '*', ['salt.minion'], output=true, timeout=30, failOnError=true)
            salt.enforceState(saltMaster, '*', ['ntp'], output=true)
            salt.runSaltProcessStep(saltMaster, '*', 'saltutil.refresh_pillar', [], null, true)
            salt.runSaltProcessStep(saltMaster, '*', 'saltutil.sync_all', [], null, true)
        }

        stage("Install Underlay"){
            salt.enforceState(saltMaster, 'I@memcached:server', 'memcached', true)
            salt.enforceState(saltMaster, 'I@rabbitmq:server', 'rabbitmq', true)
            salt.enforceState(saltMaster, 'I@mysql:server', 'mysql.server', true)
            salt.enforceState(saltMaster, 'I@mysql:client', 'mysql.client', true)

            salt.enforceState(saltMaster, 'I@keystone:server', 'keystone', true)
            salt.enforceState(saltMaster, 'I@keystone:client', 'keystone.client', true)

            salt.enforceState(saltMaster, 'I@apache:server', 'apache', true)

            salt.enforceState(saltMaster, 'I@neutron:server', 'neutron', true)
            salt.enforceState(saltMaster, 'I@neutron:gateway', 'neutron', true)
            salt.enforceState(saltMaster, 'I@neutron:client', 'neutron.client', true)

            salt.enforceState(saltMaster, 'I@ironic:api', 'ironic.api', true)
            salt.enforceState(saltMaster, 'I@ironic:conductor', 'ironic.conductor', true)
            salt.enforceState(saltMaster, 'I@ironic:client', 'ironic.client', true)

            salt.enforceState(saltMaster, 'I@tftpd_hpa:server', 'tftpd_hpa', true)
        }
        stage("Deploy nodes"){
            timeout(time: 300, unit: 'SECONDS') {
                salt.enforceState(saltMaster, 'I@ironic:deploy', 'ironic.deploy', true)
                node_names = get_node_names(saltMaster)
                wait_deployment_complete(saltMaster, node_names)
            }
        }
    }
}

