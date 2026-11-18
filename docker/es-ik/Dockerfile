FROM elasticsearch:8.14.3
COPY analysis-ik-8.14.3.zip /tmp/analysis-ik-8.14.3.zip
RUN elasticsearch-plugin install --batch file:///tmp/analysis-ik-8.14.3.zip
