#!/bin/bash

# Exit immediately if a command exits with a non-zero status.
set -e

# --- Configuration (Populate with your values or set as environment variables) ---
TOKEN_ENDPOINT_URL="${TOKEN_ENDPOINT_URL:?TOKEN_ENDPOINT_URL is not set}"
CLIENT_ID="${CLIENT_ID:?CLIENT_ID is not set}"
RESOURCE_URI="${RESOURCE_URI:?RESOURCE_URI is not set}" # The 'audience' for the token

# Path to the private key used to sign the JWT assertion.
# This MUST be a PEM-formatted private key file.
CLIENT_PRIVATE_KEY_PATH="${CLIENT_PRIVATE_KEY_PATH:?CLIENT_PRIVATE_KEY_PATH is not set}"

# Cassandra Configuration
CASSANDRA_HOST="${CASSANDRA_HOST:?CASSANDRA_HOST is not set}"
CASSANDRA_PORT="${CASSANDRA_PORT:-9042}"
CASSANDRA_DC="${CASSANDRA_DC:?CASSANDRA_DC is not set}"
CASSANDRA_USERNAME="${CASSANDRA_USERNAME:-token-user}" # Dummy username for PlainTextAuthProvider

# Path to the vendor's application JAR
APP_JAR_PATH="${APP_JAR_PATH:?APP_JAR_PATH is not set}"

# --- 1. Build the Client Assertion JWT ---
echo "Building the client assertion JWT..."

# JWT Header (Algorithm: RS256)
HEADER='{"alg":"RS256","typ":"JWT"}'

# JWT Payload
# 'iat' (issued at) and 'exp' (expiration) are timestamps. Expires in 5 minutes.
# 'sub' and 'iss' (issuer) are both the client_id.
# 'aud' (audience) is the token endpoint URL.
# 'jti' (JWT ID) is a unique identifier for the token.
NOW=$(date +%s)
EXP=$(($NOW + 300)) # Expires in 5 minutes
JTI=$(cat /proc/sys/kernel/random/uuid) # Use a random UUID for jti

PAYLOAD=$(cat <<EOF
{
  "iss": "${CLIENT_ID}",
  "sub": "${CLIENT_ID}",
  "aud": "${TOKEN_ENDPOINT_URL}",
  "iat": ${NOW},
  "exp": ${EXP},
  "jti": "${JTI}"
}
EOF
)

# Function for URL-safe Base64 encoding
base64_urlsafe() {
  openssl base64 -e -A | tr '+/' '-_' | tr -d '='
}

# Encode the Header and Payload
B64_HEADER=$(echo -n "${HEADER}" | base64_urlsafe)
B64_PAYLOAD=$(echo -n "${PAYLOAD}" | base64_urlsafe)

# Create the signature input
SIGNATURE_INPUT="${B64_HEADER}.${B64_PAYLOAD}"

# Sign the input with your private key using RS256
SIGNATURE=$(echo -n "${SIGNATURE_INPUT}" | \
  openssl dgst -sha256 -sign "${CLIENT_PRIVATE_KEY_PATH}" | \
  base64_urlsafe)

# Assemble the final JWT
CLIENT_ASSERTION="${SIGNATURE_INPUT}.${SIGNATURE}"
echo "JWT assertion built successfully."

# --- 2. Fetch the Short-Lived Cassandra Access Token ---
echo "Fetching short-lived Cassandra access token..."

# Use curl to make the POST request with x-www-form-urlencoded data
TOKEN_RESPONSE=$(curl --silent --show-error \
  --request POST \
  --url "${TOKEN_ENDPOINT_URL}" \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "client_id=${CLIENT_ID}" \
  --data-urlencode "client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer" \
  --data-urlencode "client_assertion=${CLIENT_ASSERTION}" \
  --data-urlencode "resource=${RESOURCE_URI}")

# Check if curl failed
if [ $? -ne 0 ]; then
  echo "ERROR: Failed to call the token endpoint."
  exit 1
fi

# Use jq to parse the JSON and extract the access token
CASSANDRA_ACCESS_TOKEN=$(echo "${TOKEN_RESPONSE}" | jq -r '.access_token')

if [ -z "$CASSANDRA_ACCESS_TOKEN" ] || [ "$CASSANDRA_ACCESS_TOKEN" == "null" ]; then
  echo "ERROR: Could not extract access token from the response."
  echo "Response was: ${TOKEN_RESPONSE}"
  exit 1
fi

echo "Successfully fetched Cassandra access token."

# --- 3. Run the Java Application with the Fetched Token ---
echo "Starting the Spring Boot application..."

# Use exec to replace the script process with the Java process
exec java \
  -Ddatastax-java-driver.basic.contact-points.0="${CASSANDRA_HOST}:${CASSANDRA_PORT}" \
  -Ddatastax-java-driver.basic.load-balancing-policy.local-datacenter="${CASSANDRA_DC}" \
  -Ddatastax-java-driver.advanced.auth-provider.class="PlainTextAuthProvider" \
  -Ddatastax-java-driver.advanced.auth-provider.username="${CASSANDRA_USERNAME}" \
  -Ddatastax-java-driver.advanced.auth-provider.password="${CASSANDRA_ACCESS_TOKEN}" \
  -jar "${APP_JAR_PATH}"
