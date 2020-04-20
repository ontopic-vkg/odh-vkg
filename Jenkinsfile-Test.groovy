pipeline {
    agent any

    environment {
        AWS_ACCESS_KEY_ID = credentials('AWS_ACCESS_KEY_ID')
        AWS_SECRET_ACCESS_KEY = credentials('AWS_SECRET_ACCESS_KEY')
        DOCKER_PROJECT_NAME = "odh-vkg"
        DOCKER_IMAGE_APP = "755952719952.dkr.ecr.eu-west-1.amazonaws.com/odh-vkg"
        DOCKER_TAG_APP = "test-$BUILD_NUMBER"
        DOCKER_SERVICES = "app"
        DOCKER_SERVER_IP = "63.33.73.203"
        DOCKER_SERVER_DIRECTORY = "/var/docker/odh-vkg"
        DOCKER_SERVER_PORT = "1008"
        ONTOP_QUERY_TIMEOUT = 15

        ORIGINAL_POSTGRES_HOST = "prod-postgres-tourism.co90ybcr8iim.eu-west-1.rds.amazonaws.com"
        ORIGINAL_POSTGRES_DB = "tourism"
        ORIGINAL_POSTGRES_USER = credentials('odh-vkg-test-original-postgres-username')
        ORIGINAL_POSTGRES_PASSWORD = credentials('odh-vkg-test-original-postgres-password')
        COPY_POSTGRES_HOST = "test-pg-bdp.co90ybcr8iim.eu-west-1.rds.amazonaws.com"
        COPY_POSTGRES_DB = "odh_vkg"
        COPY_POSTGRES_USER = credentials('odh-vkg-test-copy-postgres-username')
        COPY_POSTGRES_PASSWORD = credentials('odh-vkg-test-copy-postgres-password')

        FLYWAY_URL = "jdbc:postgresql://${COPY_POSTGRES_HOST}/${COPY_POSTGRES_DB}"
        FLYWAY_USER = "${COPY_POSTGRES_USER}"
        FLYWAY_PASSWORD = "${COPY_POSTGRES_PASSWORD}"
        FLYWAY_PLACEHOLDERS_ORIGINAL_HOST = "${ORIGINAL_POSTGRES_HOST}"
        FLYWAY_PLACEHOLDERS_ORIGINAL_DB = "${ORIGINAL_POSTGRES_DB}"
        FLYWAY_PLACEHOLDERS_ORIGINAL_USER = "${ORIGINAL_POSTGRES_USER}"
        FLYWAY_PLACEHOLDERS_ORIGINAL_PASSWORD = "${ORIGINAL_POSTGRES_PASSWORD}"
        FLYWAY_PLACEHOLDERS_SUBSCRIPTION_NAME = "vkgsubscription_test"
    }

    stages {
        stage('Configure') {
            steps {
                sh '''
                    cp .env.example .env
                    echo "COMPOSE_PROJECT_NAME=${DOCKER_PROJECT_NAME}" >> .env
                    echo "DOCKER_IMAGE_APP=${DOCKER_IMAGE_APP}" >> .env
                    echo "DOCKER_TAG_APP=${DOCKER_TAG_APP}" >> .env
                
                    echo "COPY_POSTGRES_HOST=${COPY_POSTGRES_HOST}" >> .env
                    echo "COPY_POSTGRES_DB=${COPY_POSTGRES_DB}" >> .env
                    echo "COPY_POSTGRES_USER=${COPY_POSTGRES_USER}" >> .env
                    echo "COPY_POSTGRES_PASSWORD=${COPY_POSTGRES_PASSWORD}" >> .env

                    echo "FLYWAY_URL=${FLYWAY_URL}" >> .env
                    echo "FLYWAY_USER=${FLYWAY_USER}" >> .env
                    echo "FLYWAY_PASSWORD=${FLYWAY_PASSWORD}" >> .env
                    echo "FLYWAY_PLACEHOLDERS_ORIGINAL_HOST=${FLYWAY_PLACEHOLDERS_ORIGINAL_HOST}" >> .env
                    echo "FLYWAY_PLACEHOLDERS_ORIGINAL_DB=${FLYWAY_PLACEHOLDERS_ORIGINAL_DB}" >> .env
                    echo "FLYWAY_PLACEHOLDERS_ORIGINAL_USER=${FLYWAY_PLACEHOLDERS_ORIGINAL_USER}" >> .env
                    echo "FLYWAY_PLACEHOLDERS_ORIGINAL_PASSWORD=${FLYWAY_PLACEHOLDERS_ORIGINAL_PASSWORD}" >> .env
                    echo "FLYWAY_PLACEHOLDERS_SUBSCRIPTION_NAME=${FLYWAY_PLACEHOLDERS_SUBSCRIPTION_NAME}" >> .env

                    sed -i -e "s%\\(DOCKER_SERVER_PORT\\s*=\\).*\\$%\\1${DOCKER_SERVER_PORT}%" .env

                    sed -i -e "s%\\(jdbc.url\\s*=\\).*\\$%\\1jdbc\\\\\\\\:postgresql\\\\\\\\://${COPY_POSTGRES_HOST}/${COPY_POSTGRES_DB}%" vkg/odh.docker.properties
                    sed -i -e "s%\\(jdbc.user\\s*=\\).*\\$%\\1${COPY_POSTGRES_USER}%" vkg/odh.docker.properties
                    sed -i -e "s%\\(jdbc.password\\s*=\\).*\\$%\\1${COPY_POSTGRES_PASSWORD}%" vkg/odh.docker.properties
                    sed -i -e "s%\\(ontop.query.defaultTimeout\\s*=\\).*\\$%\\1${ONTOP_QUERY_TIMEOUT}%" vkg/odh.docker.properties
                '''
            }
        }
        stage('Build & Push') {
            steps {
                ansiColor('xterm') {
                    sh '''
                        aws ecr get-login --region eu-west-1 --no-include-email | bash
                        docker-compose -f docker-compose.build.yml build --pull ${DOCKER_SERVICES}
                        docker-compose -f docker-compose.build.yml push ${DOCKER_SERVICES}
                    '''
                }
            }
        }
        stage('Deploy') {
            steps {
                ansiColor('xterm') {
                    sshagent(['jenkins-ssh-key']) {
                        sh """
                            ssh -o StrictHostKeyChecking=no ${DOCKER_SERVER_IP} 'AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID}" AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY}" bash -c "aws ecr get-login --region eu-west-1 --no-include-email | bash"'

                            ssh -o StrictHostKeyChecking=no ${DOCKER_SERVER_IP} 'ls -1t ${DOCKER_SERVER_DIRECTORY}/releases/ | tail -n +10 | grep -v `readlink -f ${DOCKER_SERVER_DIRECTORY}/current | xargs basename --` -- | xargs -r printf \"${DOCKER_SERVER_DIRECTORY}/releases/%s\\n\" | xargs -r rm -rf --'

                            ssh -o StrictHostKeyChecking=no ${DOCKER_SERVER_IP} 'mkdir -p ${DOCKER_SERVER_DIRECTORY}/releases/${BUILD_NUMBER}'
                            pv docker-compose.run.yml | ssh -o StrictHostKeyChecking=no ${DOCKER_SERVER_IP} 'tee ${DOCKER_SERVER_DIRECTORY}/releases/${BUILD_NUMBER}/docker-compose.yml'
                            scp -r -o StrictHostKeyChecking=no .env ${DOCKER_SERVER_IP}:${DOCKER_SERVER_DIRECTORY}/releases/${BUILD_NUMBER}/.env

                            ssh -o StrictHostKeyChecking=no ${DOCKER_SERVER_IP} 'cd ${DOCKER_SERVER_DIRECTORY}/releases/${BUILD_NUMBER} && docker-compose --no-ansi pull'
                            ssh -o StrictHostKeyChecking=no ${DOCKER_SERVER_IP} '[ -d \"${DOCKER_SERVER_DIRECTORY}/current\" ] && (cd ${DOCKER_SERVER_DIRECTORY}/current && docker-compose --no-ansi down) || true'
                            ssh -o StrictHostKeyChecking=no ${DOCKER_SERVER_IP} 'ln -sfn ${DOCKER_SERVER_DIRECTORY}/releases/${BUILD_NUMBER} ${DOCKER_SERVER_DIRECTORY}/current'
                            ssh -o StrictHostKeyChecking=no ${DOCKER_SERVER_IP} 'cd ${DOCKER_SERVER_DIRECTORY}/current && docker-compose --no-ansi up --detach'
                        """
                    }
                }
            }
	    }
    }
}
