/**
 *
 * Launch heat stack with basic k8s
 * Flow parameters:
 *   STACK_NAME                  Heat stack name
 *   STACK_TYPE                  Orchestration engine: heat, ''
 *   STACK_INSTALL               What should be installed (k8s, openstack, ...)
 *   STACK_TEST                  What should be tested (k8s, openstack, ...)
 *
 *   STACK_TEMPLATE_URL          URL to git repo with stack templates
 *   STACK_TEMPLATE_BRANCH       Stack templates repo branch
 *   STACK_TEMPLATE_CREDENTIALS  Credentials to the stack templates repo
 *   STACK_TEMPLATE              Heat stack HOT template
 *   STACK_DELETE                Delete stack when finished (bool)
 *   STACK_REUSE                 Reuse stack (don't create one)
 *   STACK_CLEANUP_JOB           Name of job for deleting Heat stack
 *
 * Expected parameters:
 * required for STACK_TYPE=heat
 *   HEAT_STACK_ENVIRONMENT       Heat stack environmental parameters
 *   HEAT_STACK_ZONE              Heat stack availability zone
 *   HEAT_STACK_PUBLIC_NET        Heat stack floating IP pool
 *   OPENSTACK_API_URL            OpenStack API address
 *   OPENSTACK_API_CREDENTIALS    Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT        OpenStack project to connect to
 *   OPENSTACK_API_PROJECT_DOMAIN Domain for OpenStack project
 *   OPENSTACK_API_PROJECT_ID     ID for OpenStack project
 *   OPENSTACK_API_USER_DOMAIN    Domain for OpenStack user
 *   OPENSTACK_API_CLIENT         Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION        Version of the OpenStack API (2/3)
 *
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *
 * required for STACK_TYPE=NONE or empty string
 *   SALT_MASTER_URL            URL of Salt-API

 *   K8S_API_SERVER             Kubernetes API address
 *   K8S_CONFORMANCE_IMAGE      Path to docker image with conformance e2e tests
 *
 *   TEMPEST_IMAGE_LINK         Tempest image link
 *
 * optional parameters for overwriting soft params
 *   KUBERNETES_HYPERKUBE_IMAGE  Docker repository and tag for hyperkube image
 *   CALICO_CNI_IMAGE            Docker repository and tag for calico CNI image
 *   CALICO_NODE_IMAGE           Docker repository and tag for calico node image
 *   CALICOCTL_IMAGE             Docker repository and tag for calicoctl image
 *   MTU                         MTU for Calico
 *   NETCHECKER_AGENT_IMAGE      Docker repository and tag for netchecker agent image
 *   NETCHECKER_SERVER_IMAGE     Docker repository and tag for netchecker server image
 *
 */

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
orchestrate = new com.mirantis.mk.Orchestrate()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()

_MAX_PERMITTED_STACKS = 2

def installOpenstackInfra(master, openstack_services='glusterfs') {
    def salt = new com.mirantis.mk.Salt()

    if (common.checkContains('openstack_services', 'glusterfs')){
        // Install glusterfs
        salt.enforceState(master, 'I@glusterfs:server', 'glusterfs.server.service', true)
    }

    // Install keepaliveds
    //runSaltProcessStep(master, 'I@keepalived:cluster', 'state.sls', ['keepalived'], 1)
    salt.enforceState(master, 'I@keepalived:cluster and *01*', 'keepalived', true)
    salt.enforceState(master, 'I@keepalived:cluster', 'keepalived', true)

    // Check the keepalived VIPs
    salt.runSaltProcessStep(master, 'I@keepalived:cluster', 'cmd.run', ['ip a | grep 172.16.10.2'])

    if (common.checkContains('openstack_services', 'glusterfs')){
        withEnv(['ASK_ON_ERROR=false']){
            retry(5) {
                salt.enforceState(master, 'I@glusterfs:server and *01*', 'glusterfs.server.setup', true)
            }
        }

        salt.runSaltProcessStep(master, 'I@glusterfs:server', 'cmd.run', ['gluster peer status'], null, true)
        salt.runSaltProcessStep(master, 'I@glusterfs:server', 'cmd.run', ['gluster volume status'], null, true)

    }
    // Install rabbitmq
    withEnv(['ASK_ON_ERROR=false']){
        retry(2) {
            salt.enforceState(master, 'I@rabbitmq:server', 'rabbitmq', true)
        }
    }

    // Check the rabbitmq status
    salt.runSaltProcessStep(master, 'I@rabbitmq:server', 'cmd.run', ['rabbitmqctl cluster_status'])

    // Install galera
    withEnv(['ASK_ON_ERROR=false']){
        retry(2) {
            salt.enforceState(master, 'I@galera:master', 'galera', true)
        }
    }
    salt.enforceState(master, 'I@galera:slave', 'galera', true)

    // Check galera status
    salt.runSaltProcessStep(master, 'I@galera:master', 'mysql.status')
    salt.runSaltProcessStep(master, 'I@galera:slave', 'mysql.status')

    // // Setup mysql client
    // salt.enforceState(master, 'I@mysql:client', 'mysql.client', true)

    // Install haproxy
    salt.enforceState(master, 'I@haproxy:proxy', 'haproxy', true)
    salt.runSaltProcessStep(master, 'I@haproxy:proxy', 'service.status', ['haproxy'])
    salt.runSaltProcessStep(master, 'I@haproxy:proxy', 'service.restart', ['rsyslog'])

    // Install memcached
    salt.enforceState(master, 'I@memcached:server', 'memcached', true)
}

def installOpenstackIronic(master){
    def salt = new com.mirantis.mk.Salt()

    salt.enforceState(master, 'I@ironic:api and ctl01*', 'ironic.api', true)
    salt.enforceState(master, 'I@ironic:api', 'ironic.api', true)
    salt.enforceState(master, 'I@ironic:conductor', 'ironic.conductor', true)
    salt.enforceState(master, 'I@ironic:conductor', 'apache', true)
    salt.runSaltProcessStep(master, 'I@nova:compute', 'service.restart', ['nova-compute'])

    salt.enforceState(master, 'I@tftpd_hpa:server', 'tftpd_hpa', true)
    salt.enforceState(master, 'I@ironic:client', 'ironic.client', true)
}

def installOpenstackControl(master) {
    def salt = new com.mirantis.mk.Salt()

    try {
        OPENSTACK_SERVICES = OPENSTACK_SERVICES
        env['OPENSTACK_SERVICES'] = OPENSTACK_SERVICES
    } catch (MissingPropertyException e) {
        OPENSTACK_SERVICES='horizon'
    }


    if (common.checkContains('OPENSTACK_SERVICES', 'horizon')){
    // Install horizon dashboard
        salt.enforceState(master, 'I@horizon:server', 'horizon', true)
        salt.enforceState(master, 'I@nginx:server', 'nginx', true)
    }

    // setup keystone service
    //runSaltProcessStep(master, 'I@keystone:server', 'state.sls', ['keystone.server'], 1)
    salt.enforceState(master, 'I@keystone:server and *01*', 'keystone.server', true)
    salt.enforceState(master, 'I@keystone:server', 'keystone.server', true)
    // populate keystone services/tenants/roles/users

    // keystone:client must be called locally
    //salt.runSaltProcessStep(master, 'I@keystone:client', 'cmd.run', ['salt-call state.sls keystone.client'], null, true)
    salt.runSaltProcessStep(master, 'I@keystone:server', 'service.restart', ['apache2'])
    sleep(30)
    salt.enforceState(master, 'I@keystone:client', 'keystone.client', true)
    salt.enforceState(master, 'I@keystone:client', 'keystone.client', true)
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonercv3; openstack service list'], null, true)

    // Install glance and ensure glusterfs clusters
    //runSaltProcessStep(master, 'I@glance:server', 'state.sls', ['glance.server'], 1)
    salt.enforceState(master, 'I@glance:server and *01*', 'glance.server', true)
    salt.enforceState(master, 'I@glance:server', 'glance.server', true)
    if (common.checkContains('OPENSTACK_SERVICES', 'glusterfs')){
        salt.enforceState(master, 'I@glance:server', 'glusterfs.client', true)
    }`

    // Update fernet tokens before doing request on keystone server
    salt.enforceState(master, 'I@keystone:server', 'keystone.server', true)

    // Check glance service
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; glance image-list'], null, true)

    // Install and check nova service
    //runSaltProcessStep(master, 'I@nova:controller', 'state.sls', ['nova'], 1)
    salt.enforceState(master, 'I@nova:controller and *01*', 'nova.controller', true, false)
    salt.enforceState(master, 'I@nova:controller and *01*', 'nova.controller', true, false)
    salt.enforceState(master, 'I@nova:controller and *01*', 'nova.controller', true)
    salt.enforceState(master, 'I@nova:controller', 'nova.controller', true)
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; nova service-list'], null, true)

    // Install and check cinder service
    //runSaltProcessStep(master, 'I@cinder:controller', 'state.sls', ['cinder'], 1)
    salt.enforceState(master, 'I@cinder:controller and *01*', 'cinder', true)
    salt.enforceState(master, 'I@cinder:controller', 'cinder', true)
    salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; cinder list'], null, true)
    // Install heat service
    //salt.runSaltProcessStep(master, 'I@heat:server', 'state.sls', ['heat'], 1)
    //salt.enforceState(master, 'I@heat:server and *01*', 'heat', true)
    //salt.enforceState(master, 'I@heat:server', 'heat', true)
    //salt.runSaltProcessStep(master, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; heat resource-type-list'], null, true)

    // Restart nova api
    salt.runSaltProcessStep(master, 'I@nova:controller', 'service.restart', ['nova-api'])

}

timestamps {
    node {
        // try to get STACK_INSTALL or fallback to INSTALL if exists
        try {
          def temporary = STACK_INSTALL
        } catch (MissingPropertyException e) {
          try {
            STACK_INSTALL = INSTALL
            env['STACK_INSTALL'] = INSTALL
          } catch (MissingPropertyException e2) {
            common.errorMsg("Property STACK_INSTALL or INSTALL not found!")
          }
        }
        try {
            //
            // Prepare machines
            //
            stage ('Create infrastructure') {

                if (STACK_TYPE == 'heat') {
                    // value defaults
                    def openstackCloud
                    def openstackVersion = OPENSTACK_API_CLIENT ? OPENSTACK_API_CLIENT : 'liberty'
                    def openstackEnv = "${env.WORKSPACE}/venv"

                    if (STACK_REUSE.toBoolean() == true && STACK_NAME == '') {
                        error("If you want to reuse existing stack you need to provide it's name")
                    }

                    if (STACK_REUSE.toBoolean() == false) {
                        // Don't allow to set custom heat stack name
                        wrap([$class: 'BuildUser']) {
                            if (env.BUILD_USER_ID) {
                                STACK_NAME = "${env.BUILD_USER_ID}-${JOB_NAME}-${BUILD_NUMBER}"
                            } else {
                                STACK_NAME = "jenkins-${JOB_NAME}-${BUILD_NUMBER}"
                            }
                            currentBuild.description = STACK_NAME
                        }
                    }

                    // set description
                    currentBuild.description = "${STACK_NAME}"

                    // get templates
                    //git.checkoutGitRepository('template', STACK_TEMPLATE_URL, STACK_TEMPLATE_BRANCH, STACK_TEMPLATE_CREDENTIALS)

                    // create openstack env
                    openstack.setupOpenstackVirtualenv(openstackEnv, openstackVersion)
                    openstackCloud = openstack.createOpenstackEnv(
                        OPENSTACK_API_URL, OPENSTACK_API_CREDENTIALS,
                        OPENSTACK_API_PROJECT, OPENSTACK_API_PROJECT_DOMAIN,
                        OPENSTACK_API_PROJECT_ID, OPENSTACK_API_USER_DOMAIN,
                        OPENSTACK_API_VERSION)
                    openstack.getKeystoneToken(openstackCloud, openstackEnv)
                    //
                    // Verify possibility of create stack for given user and stack type
                    //
                    wrap([$class: 'BuildUser']) {
                        if (env.BUILD_USER_ID && !env.BUILD_USER_ID.equals("jenkins") && !env.BUILD_USER_ID.equals("mceloud") && !STACK_REUSE.toBoolean()) {
                            def existingStacks = openstack.getStacksForNameContains(openstackCloud, "${env.BUILD_USER_ID}-${JOB_NAME}", openstackEnv)
                            if(existingStacks.size() >= _MAX_PERMITTED_STACKS){
                                STACK_DELETE = "false"
                                throw new Exception("You cannot create new stack, you already have ${_MAX_PERMITTED_STACKS} stacks of this type (${JOB_NAME}). \nStack names: ${existingStacks}")
                            }
                        }
                    }
                    // launch stack
                    if (STACK_REUSE.toBoolean() == false) {
                        stage('Launch new Heat stack') {
                            // create stack
                            envParams = [
                                'instance_zone': HEAT_STACK_ZONE,
                                'public_net': HEAT_STACK_PUBLIC_NET
                            ]
                            try {
                                envParams.put('cfg_reclass_branch', HEAT_STACK_RECLASS_BRANCH)
                            } catch (MissingPropertyException e) {}
                            try {
                                envParams.put('cfg_reclass_address', HEAT_STACK_RECLASS_ADDRESS)
                            } catch (MissingPropertyException e) {}
                            openstack.createHeatStack(openstackCloud, STACK_NAME, STACK_TEMPLATE, envParams, HEAT_STACK_ENVIRONMENT, openstackEnv)
                        }
                    }

                    // get SALT_MASTER_URL
                    saltMasterHost = openstack.getHeatStackOutputParam(openstackCloud, STACK_NAME, 'salt_master_ip', openstackEnv)
                    currentBuild.description = "${STACK_NAME}: ${saltMasterHost}"

                    SALT_MASTER_URL = "http://${saltMasterHost}:6969"
                }
            }

            //
            // Connect to Salt master
            //

            def saltMaster
            stage('Connect to Salt API') {
                saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            //
            // Install
            //

            if (common.checkContains('STACK_INSTALL', 'core')) {
                stage('Install core infrastructure') {
                    orchestrate.installFoundationInfra(saltMaster)

                    if (common.checkContains('STACK_INSTALL', 'kvm')) {
                        orchestrate.installInfraKvm(saltMaster)
                        orchestrate.installFoundationInfra(saltMaster)
                    }

                    orchestrate.validateFoundationInfra(saltMaster)
                }
            }

            // install k8s
            if (common.checkContains('STACK_INSTALL', 'k8s')) {

                stage('Overwrite Kubernetes parameters') {

                    // Overwrite Kubernetes vars if specified
                    if (env.getEnvironment().containsKey('KUBERNETES_HYPERKUBE_IMAGE')) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_hyperkube_image', KUBERNETES_HYPERKUBE_IMAGE])
                    }
                    if (env.getEnvironment().containsKey('MTU')) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_mtu', MTU])
                    }

                    // Overwrite Calico vars if specified
                    if (env.getEnvironment().containsKey('CALICO_CNI_IMAGE')) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_calico_cni_image', CALICO_CNI_IMAGE])
                    }
                    if (env.getEnvironment().containsKey('CALICO_NODE_IMAGE')) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_calico_image', CALICO_NODE_IMAGE])
                    }
                    if (env.getEnvironment().containsKey('CALICOCTL_IMAGE')) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_calicoctl_image', CALICOCTL_IMAGE])
                    }
                    if (env.getEnvironment().containsKey('CALICO_POLICY_IMAGE')) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_calico_policy_image', CALICO_POLICY_IMAGE])
                    }

                    // Overwrite Virtlet image if specified
                    if (env.getEnvironment().containsKey('VIRTLET_IMAGE')) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_virtlet_image', VIRTLET_IMAGE])
                    }

                    // Overwrite netchecker vars if specified
                    if (env.getEnvironment().containsKey('NETCHECKER_AGENT_IMAGE')) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_netchecker_agent_image', NETCHECKER_AGENT_IMAGE])
                    }
                    if (env.getEnvironment().containsKey('NETCHECKER_SERVER_IMAGE')) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_netchecker_server_image', NETCHECKER_SERVER_IMAGE])
                    }

                    // Overwrite docker version if specified
                    if (env.getEnvironment().containsKey('DOCKER_ENGINE')) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_docker_package', DOCKER_ENGINE])
                    }

                    // Overwrite addons vars if specified
                    if (env.getEnvironment().containsKey('HELM_ENABLED')) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_helm_enabled', HELM_ENABLED])
                    }
                    if (env.getEnvironment().containsKey('NETCHECKER_ENABLED')) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_netchecker_enabled', NETCHECKER_ENABLED])
                    }
                    if (env.getEnvironment().containsKey('CALICO_POLICY_ENABLED')) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_calico_policy_enabled', CALICO_POLICY_ENABLED])
                    }
                    if (env.getEnvironment().containsKey('VIRTLET_ENABLED')) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_virtlet_enabled', VIRTLET_ENABLED])
                    }
                    if (env.getEnvironment().containsKey('KUBE_NET_MANAGER_ENABLED')) {
                        salt.runSaltProcessStep(saltMaster, 'I@salt:master', 'reclass.cluster_meta_set', ['kubernetes_kube_network_manager_enabled', KUBE_NET_MANAGER_ENABLED])
                    }
                 }

                // If k8s install with contrail network manager then contrail need to be install first
                if (common.checkContains('STACK_INSTALL', 'contrail')) {
                    stage('Install Contrail for Kubernetes') {
                        orchestrate.installContrailNetwork(saltMaster)
                        orchestrate.installContrailCompute(saltMaster)
                        orchestrate.installKubernetesContrailCompute(saltMaster)
                    }
                }

                stage('Install Kubernetes infra') {
                    orchestrate.installKubernetesInfra(saltMaster)
                }

                stage('Install Kubernetes control') {
                    orchestrate.installKubernetesControl(saltMaster)
                }
            }

            // install openstack
            if (common.checkContains('STACK_INSTALL', 'openstack')) {
                // install Infra and control, tests, ...

                stage('Install OpenStack infra') {
                    installOpenstackInfra(saltMaster, OPENSTACK_SERVICES)
                }

                stage('Install OpenStack control') {
                    installOpenstackControl(saltMaster)
                }

                stage('Install OpenStack network') {

                    if (common.checkContains('STACK_INSTALL', 'contrail')) {
                        orchestrate.installContrailNetwork(saltMaster)
                    } else if (common.checkContains('STACK_INSTALL', 'ovs')) {
                        orchestrate.installOpenstackNetwork(saltMaster)
                    }

                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; neutron net-list'])
                    salt.runSaltProcessStep(saltMaster, 'I@keystone:server', 'cmd.run', ['. /root/keystonerc; nova net-list'])
                }

                stage('Install OpenStack compute') {
                    orchestrate.installOpenstackCompute(saltMaster)

                    if (common.checkContains('STACK_INSTALL', 'contrail')) {
                        orchestrate.installContrailCompute(saltMaster)
                    }
                }
                if (common.checkContains('OPENSTACK_SERVICES', 'ironic')) {
                    stage('Install OpenStack Ironic') {
                        installOpenstackIronic(saltMaster)
                    }
                }

            }


            if (common.checkContains('STACK_INSTALL', 'sl-legacy')) {
                stage('Install StackLight v1') {
                    orchestrate.installStacklightv1Control(saltMaster)
                    orchestrate.installStacklightv1Client(saltMaster)
                }
            }

            if (common.checkContains('STACK_INSTALL', 'stacklight')) {
                stage('Install StackLight') {
                    orchestrate.installDockerSwarm(saltMaster)
                    orchestrate.installStacklight(saltMaster)
                }
            }

            //
            // Test
            //
            def artifacts_dir = '_artifacts/'

            if (common.checkContains('STACK_TEST', 'k8s')) {
                stage('Run k8s bootstrap tests') {
                    def image = 'tomkukral/k8s-scripts'
                    def output_file = image.replaceAll('/', '-') + '.output'

                    // run image
                    test.runConformanceTests(saltMaster, K8S_API_SERVER, image)

                    // collect output
                    sh "mkdir -p ${artifacts_dir}"
                    file_content = salt.getFileContent(saltMaster, 'ctl01*', '/tmp/' + output_file)
                    writeFile file: "${artifacts_dir}${output_file}", text: file_content
                    sh "cat ${artifacts_dir}${output_file}"

                    // collect artifacts
                    archiveArtifacts artifacts: "${artifacts_dir}${output_file}"
                }

                stage('Run k8s conformance e2e tests') {
                    //test.runConformanceTests(saltMaster, K8S_API_SERVER, K8S_CONFORMANCE_IMAGE)

                    def image = K8S_CONFORMANCE_IMAGE
                    def output_file = image.replaceAll('/', '-') + '.output'

                    // run image
                    test.runConformanceTests(saltMaster, K8S_API_SERVER, image)

                    // collect output
                    sh "mkdir -p ${artifacts_dir}"
                    file_content = salt.getFileContent(saltMaster, 'ctl01*', '/tmp/' + output_file)
                    writeFile file: "${artifacts_dir}${output_file}", text: file_content
                    sh "cat ${artifacts_dir}${output_file}"

                    // collect artifacts
                    archiveArtifacts artifacts: "${artifacts_dir}${output_file}"
                }
            }

            if (common.checkContains('STACK_TEST', 'openstack')) {
                stage('Run OpenStack tests') {
                    test.runTempestTests(saltMaster, TEMPEST_IMAGE_LINK)
                }

                stage('Copy Tempest results to config node') {
                    test.copyTempestResults(saltMaster)
                }
            }

            stage('Finalize') {
                if (STACK_INSTALL != '') {
                    try {
                        salt.runSaltProcessStep(saltMaster, '*', 'state.apply', [], null, true)
                    } catch (Exception e) {
                        common.warningMsg('State apply failed but we should continue to run')
                        throw e
                    }
                }
            }

        } catch (Throwable e) {
            currentBuild.result = 'FAILURE'
            throw e
        } finally {


            //
            // Clean
            //

            if (STACK_TYPE == 'heat') {
                // send notification
                common.sendNotification(currentBuild.result, STACK_NAME, ["slack"])

                if (STACK_DELETE.toBoolean() == true) {
                    common.errorMsg('Heat job cleanup triggered')
                    stage('Trigger cleanup job') {
                        build(job: STACK_CLEANUP_JOB, parameters: [
                            [$class: 'StringParameterValue', name: 'STACK_NAME', value: STACK_NAME],
                            [$class: 'StringParameterValue', name: 'STACK_TYPE', value: STACK_TYPE],
                            [$class: 'StringParameterValue', name: 'OPENSTACK_API_URL', value: OPENSTACK_API_URL],
                            [$class: 'StringParameterValue', name: 'OPENSTACK_API_CREDENTIALS', value: OPENSTACK_API_CREDENTIALS],
                            [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                            [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_DOMAIN', value: OPENSTACK_API_PROJECT_DOMAIN],
                            [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_ID', value: OPENSTACK_API_PROJECT_ID],
                            [$class: 'StringParameterValue', name: 'OPENSTACK_API_USER_DOMAIN', value: OPENSTACK_API_USER_DOMAIN],
                            [$class: 'StringParameterValue', name: 'OPENSTACK_API_CLIENT', value: OPENSTACK_API_CLIENT],
                            [$class: 'StringParameterValue', name: 'OPENSTACK_API_VERSION', value: OPENSTACK_API_VERSION]
                        ])
                    }
                } else {
                    if (currentBuild.result == 'FAILURE') {
                        common.errorMsg("Deploy job FAILED and was not deleted. Please fix the problem and delete stack on you own.")

                        if (SALT_MASTER_URL) {
                            common.errorMsg("Salt master URL: ${SALT_MASTER_URL}")
                        }
                    }

                }
            }
        }
    }
}
