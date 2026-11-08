FROM ubuntu:latest
LABEL authors="ha"

ENTRYPOINT ["top", "-b"]