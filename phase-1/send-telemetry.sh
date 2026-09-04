#!/usr/bin/env bash
# Sends one trace (2 spans), one correlated log record, and one metric
# straight into the Collector's OTLP/HTTP receiver. No SDK, no app.
set -euo pipefail

ENDPOINT="${OTLP_HTTP:-http://localhost:4318}"

TRACE_ID=$(openssl rand -hex 16)
ROOT_SPAN=$(openssl rand -hex 8)
CHILD_SPAN=$(openssl rand -hex 8)

NOW_NS=$(( $(date +%s) * 1000000000 ))
ROOT_END=$(( NOW_NS + 118000000 ))     # 118 ms
CHILD_START=$(( NOW_NS + 12000000 ))
CHILD_END=$(( NOW_NS + 26000000 ))     #  14 ms

echo "trace_id = $TRACE_ID"

post () {  # $1 = signal path, $2 = json
  curl -sS -o /dev/null -w "  %{http_code}  $1\n" \
    -X POST "$ENDPOINT/v1/$1" \
    -H 'Content-Type: application/json' \
    -d "$2"
}

# ---------------------------------------------------------------- traces
post traces "$(cat <<JSON
{ "resourceSpans": [{
  "resource": { "attributes": [
    { "key": "service.name",           "value": { "stringValue": "order-api" } },
    { "key": "service.version",        "value": { "stringValue": "0.1.0" } },
    { "key": "deployment.environment", "value": { "stringValue": "local" } }
  ]},
  "scopeSpans": [{
    "scope": { "name": "hand-rolled-curl", "version": "1.0.0" },
    "spans": [
      { "traceId": "$TRACE_ID", "spanId": "$ROOT_SPAN",
        "name": "POST /orders", "kind": 2,
        "startTimeUnixNano": "$NOW_NS", "endTimeUnixNano": "$ROOT_END",
        "attributes": [
          { "key": "http.request.method",      "value": { "stringValue": "POST" } },
          { "key": "http.route",               "value": { "stringValue": "/orders" } },
          { "key": "http.response.status_code","value": { "intValue": "201" } }
        ],
        "status": { "code": 1 } },
      { "traceId": "$TRACE_ID", "spanId": "$CHILD_SPAN", "parentSpanId": "$ROOT_SPAN",
        "name": "publish orders.created", "kind": 4,
        "startTimeUnixNano": "$CHILD_START", "endTimeUnixNano": "$CHILD_END",
        "attributes": [
          { "key": "messaging.system",           "value": { "stringValue": "rabbitmq" } },
          { "key": "messaging.destination.name", "value": { "stringValue": "orders" } }
        ],
        "status": { "code": 1 } }
    ]
  }]
}]}
JSON
)"

# ------------------------------------------------------------------ logs
# Same trace_id + span_id as the child span. This is what makes
# "click a span, see its logs" work in Grafana or Kibana later.
post logs "$(cat <<JSON
{ "resourceLogs": [{
  "resource": { "attributes": [
    { "key": "service.name", "value": { "stringValue": "order-api" } }
  ]},
  "scopeLogs": [{
    "scope": { "name": "hand-rolled-curl" },
    "logRecords": [{
      "timeUnixNano": "$CHILD_START",
      "severityNumber": 9, "severityText": "INFO",
      "body": { "stringValue": "published orders.created for order 41c9" },
      "attributes": [
        { "key": "order.id", "value": { "stringValue": "41c9" } }
      ],
      "traceId": "$TRACE_ID", "spanId": "$CHILD_SPAN"
    }]
  }]
}]}
JSON
)"

# --------------------------------------------------------------- metrics
post metrics "$(cat <<JSON
{ "resourceMetrics": [{
  "resource": { "attributes": [
    { "key": "service.name", "value": { "stringValue": "order-api" } }
  ]},
  "scopeMetrics": [{
    "scope": { "name": "hand-rolled-curl" },
    "metrics": [{
      "name": "orders.created",
      "unit": "{order}",
      "description": "Orders accepted by the API",
      "sum": {
        "aggregationTemporality": 2,
        "isMonotonic": true,
        "dataPoints": [{
          "startTimeUnixNano": "$NOW_NS", "timeUnixNano": "$ROOT_END",
          "asInt": "1",
          "attributes": [
            { "key": "channel", "value": { "stringValue": "web" } }
          ]
        }]
      }
    }]
  }]
}]}
JSON
)"
