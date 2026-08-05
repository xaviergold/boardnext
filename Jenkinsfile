pipeline {
    agent any
    
    environment {
        DOCKERHUB_CREDENTIALS = credentials('docker-token')
    }
    
    triggers {
        pollSCM('* * * * *')
    }
    
    stages {
        stage("Compile") {
            steps {
                dir('restapi'){
                    sh "./gradlew clean compileJava -x test"
                }
            }
        }
        stage("Build") {
            steps {
                dir('restapi'){
                    sh "./gradlew clean build -x test"
                }
                sh """
                    cp ./restapi/build/libs/BoardRest-0.0.1-SNAPSHOT.jar ./docker/boardrest/boardrest.jar
                    ls -lah ./docker/boardrest/
                """
            }
        }
        stage("Docker Login") {
            steps {
                sh 'echo $DOCKERHUB_CREDENTIALS_PSW | docker login -u $DOCKERHUB_CREDENTIALS_USR --password-stdin'
            }
        }
        stage("Docker Image Build") {
            steps {
                sh "docker build --no-cache -t redleon1/apache-boardnext:${BUILD_NUMBER} ./docker/apache2/"
                sh "docker build --no-cache -t redleon1/boardrest-boardnext:${BUILD_NUMBER} ./docker/boardrest/"
            }
        }
        stage("Docker Image Push") {
            steps {
                sh "docker push redleon1/apache-boardnext:${BUILD_NUMBER}"
                sh "docker push redleon1/boardrest-boardnext:${BUILD_NUMBER}"
            }
        }
        stage("Docker Image Clean up") {
            steps {
                sh "docker image rm redleon1/apache-boardnext:${BUILD_NUMBER}"
                sh "docker image rm redleon1/boardrest-boardnext:${BUILD_NUMBER}"
            }
        }
        stage("Deploy") {
            steps {
                sh "sed -i 's/{{VERSION}}/${BUILD_NUMBER}/g' ./kubernetes/apache2.yml"
                sh "sed -i 's/{{VERSION}}/${BUILD_NUMBER}/g' ./kubernetes/boardrest.yml"
                sh "kubectl apply -f ./kubernetes/boardrest.yml"
                sh "kubectl apply -f ./kubernetes/apache2.yml"
                sh "kubectl apply -f ./kubernetes/ingress.yml"
                
                // 배포 완료 후 쿠버네티스에서 Ingress IP를 가져와 젠킨스 변수에 할당 (.trim()으로 공백 제거)
                script {
                    env.INGRESS_IP = sh(
                        script: "kubectl get svc ingress-nginx-controller -n ingress-nginx -o jsonpath='{.status.loadBalancer.ingress[0].ip}'",
                        returnStdout: true
                    ).trim()
                    
                    // 젠킨스 빌드 콘솔 로그에 IP 출력
                    echo "========================================="
                    echo "배포 완료! Ingress IP: ${env.INGRESS_IP}"
                    echo "========================================="
                }
            }
            post {
                success {                    
                    slackSend(channel: "#it교육", color: "#2C953C", message: "boardnext 배포가 성공하였습니다. (접속 IP: ${env.INGRESS_IP})")
                }
                failure {
                    slackSend(channel: "#it교육", color: "#FF3232", message: "boardnext 배포가 실패하였습니다.")
                }
                always {
                    sh "docker builder prune -f"
                }
            }
        }
    }
}
