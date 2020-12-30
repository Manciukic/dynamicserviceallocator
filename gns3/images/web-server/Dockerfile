FROM httpd:2.4.46-alpine
COPY ./public-html/ /usr/local/apache2/htdocs/
COPY ./bin /bin/
RUN apk --no-cache add curl
CMD /bin/sh /bin/boot.sh

