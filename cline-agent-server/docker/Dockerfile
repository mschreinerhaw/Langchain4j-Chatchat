# Build context: only runtime/ and service/. RUNTIME_BASE supplies OS/Node tools
# to an intermediate stage; its application/SDK layers never enter the final image.
ARG RUNTIME_BASE
FROM ${RUNTIME_BASE} AS tools
USER root
COPY service/docker/assemble-rootfs.py /tmp/assemble-rootfs.py
COPY runtime/ /tmp/native-runtime/
RUN python3 /tmp/assemble-rootfs.py /clean-root /tmp/native-runtime

FROM scratch
COPY --from=tools /clean-root/ /
COPY runtime/ /srv/runtime/
COPY service/ /srv/service/
WORKDIR /srv/service
ENV CLINE_SERVER_CONFIG=/run/cline-srv.json PATH=/usr/local/bin:/usr/bin:/bin HOME=/home/cline LANG=C.UTF-8
USER 1000:1000
RUN node /srv/service/scripts/verify-runtime.mjs /srv/runtime
EXPOSE 18326
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 CMD ["node", "-e", "const c=JSON.parse(require('fs').readFileSync(process.env.CLINE_SERVER_CONFIG));fetch('http://127.0.0.1:'+(c.port??18326)+'/health/ready',{headers:{Authorization:'Bearer '+c.apiToken}}).then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"]
CMD ["node", "/srv/service/src/server.mjs"]
