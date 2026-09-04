#!/bin/sh
# Steady traffic with a deliberate minority of failures, so the dashboards and
# the trace search have something other than one happy path in them.
API="${API:-http://order-api:3001}"
while true; do
  n=$((RANDOM % 100))
  if [ "$n" -lt 10 ]; then
    # 400 at the API: never reaches RabbitMQ, so the trace is one span long.
    body='{"sku":"SKU-BAD"}'
  elif [ "$n" -lt 20 ]; then
    # accepted, then fails in the worker after the receipt is written
    body='{"sku":"SKU-BULK","qty":95,"channel":"partner"}'
  else
    body="{\"sku\":\"SKU-$((RANDOM % 20))\",\"qty\":$((RANDOM % 5 + 1)),\"channel\":\"web\"}"
  fi
  curl -s -o /dev/null -X POST "$API/orders" -H 'content-type: application/json' -d "$body"
  sleep 1
done
