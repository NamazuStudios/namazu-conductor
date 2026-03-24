FROM nginx:alpine
EXPOSE 80
COPY test_entrypoint.sh /test_entrypoint.sh
RUN chmod +x /test_entrypoint.sh
ENTRYPOINT ["/test_entrypoint.sh"]
