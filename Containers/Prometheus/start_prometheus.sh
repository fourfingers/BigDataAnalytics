#!/bin/sh

PROMETHEUS_CONFIG_FILE=/etc/prometheus/prometheus.yml
PROMETHEUS_CONFIG_TEMPLATE_FILE=${PROMETHEUS_CONFIG_FILE}.template

# Replace environment variables in the prometheus.yml template using sed
  cat ${PROMETHEUS_CONFIG_TEMPLATE_FILE} \
  | sed "s|\${MONITOR_TOOL_HOST}|${MONITOR_TOOL_HOST}|g" \
  | sed "s|\${MONITOR_TOOL_PORT}|${MONITOR_TOOL_PORT}|g" \
  | sed "s|\${MONGODB_EXPORTER_HOST}|${MONGODB_EXPORTER_HOST}|g" \
  | sed "s|\${MONGODB_EXPORTER_PORT}|${MONGODB_EXPORTER_PORT}|g" \
  > ${PROMETHEUS_CONFIG_FILE}

# Start Prometheus with the updated config
/bin/prometheus --config.file=${PROMETHEUS_CONFIG_FILE}
