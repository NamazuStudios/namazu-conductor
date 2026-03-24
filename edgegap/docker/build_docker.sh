#!/bin/bash

TAG="1.0.0"
IMAGE='nginx_it'
REPOSITORY='registry.edgegap.com'
PROJECT='namazu-studios-c726io7z5wrr'

docker build -t $IMAGE:$TAG $IMAGE
docker tag $IMAGE:$TAG $REPOSITORY/$PROJECT/$IMAGE:$TAG
docker push $REPOSITORY/$PROJECT/$IMAGE:$TAG
