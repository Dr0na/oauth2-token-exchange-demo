FROM quay.io/keycloak/keycloak:26.2.5

USER root
COPY scripts/setup-token-exchange.sh /opt/keycloak/setup-token-exchange.sh
RUN chmod +x /opt/keycloak/setup-token-exchange.sh
USER keycloak

ENTRYPOINT ["/bin/bash", "/opt/keycloak/setup-token-exchange.sh"]
