readonly TAG=$1
readonly DOCKER_IMAGE=nachocode/system-rep
sbt assembly && docker build -t "$DOCKER_IMAGE:$TAG" .
