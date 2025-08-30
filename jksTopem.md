You are absolutely right to ask this. This is the final piece of the puzzle. My previous script assumed you had PEM files, but you have a JKS file. This is a crucial difference.

**Yes, you absolutely need the JKS file.** The private key inside that JKS file is the secret credential you need to sign the JWT.

The challenge is that standard shell tools like `openssl` cannot directly read a private key from a Java KeyStore (`.jks`) file. We need to perform a **one-time conversion** to extract the private key into the PEM format that `openssl` can use.

Here is the complete workflow, starting with converting your JKS file.

### Step 1 (One-Time Task): Extract Private Key from JKS to PEM

You will perform these steps once on your local machine to prepare the key file for your script. You will need `keytool` (from the JDK) and `openssl`.

First, you need to know the **alias** of the key entry within your JKS file. If you don't know it, you can list the contents of the JKS:
```bash
keytool -list -v -keystore /path/to/your/keystore.jks
# It will prompt for the keystore password
```
Look for an entry of type `PrivateKeyEntry` and note its "Alias name".

#### A. Convert JKS to PKCS12
PKCS12 is a more standard format that `openssl` can understand.
```bash
keytool -importkeystore \
    -srckeystore /path/to/your/keystore.jks \
    -destkeystore my-keystore.p12 \
    -deststoretype PKCS12 \
    -srcalias <YOUR_KEY_ALIAS> \
    -destalias <YOUR_KEY_ALIAS>
```
You will be prompted for the source JKS password and to create a new password for the `.p12` file. For simplicity, you can reuse the same password.

#### B. Extract the Private Key from the PKCS12 file
Now, use `openssl` to convert the `.p12` file into a standard, unencrypted PEM private key file.
```bash
openssl pkcs12 -in my-keystore.p12 -nodes -nocerts -out client-private-key.pem
```
You will be prompted for the `.p12` file's password you just created.
*   The `-nodes` flag means "no DES," which saves the key in an unencrypted format. This is important so the script doesn't need to be prompted for a password to read the key.
*   The `-nocerts` flag ensures only the private key is extracted.

You now have a file named `client-private-key.pem`. **This is the file the wrapper script needs.**

---

### Step 2: Use the Extracted Key in the Wrapper Script

Now you can use the **exact same `run-app.sh` script** from the previous answer. The only change is in how you set the environment variables before running it.

Your certificate (`.cert` file) and your original JKS file are **not needed** by the script, because their only purpose was to provide the private key, which you have now extracted.

### Putting It All Together: The Complete Workflow

1.  **Preparation (One-Time):**
    *   Run the `keytool` and `openssl` commands shown above to generate `client-private-key.pem` from your `keystore.jks`.
    *   Securely place the `client-private-key.pem` file in the environment where your application will run (e.g., in your Docker image or on your VM).

2.  **Configuration (At Runtime):**
    *   Set the environment variables. **Crucially, point `CLIENT_PRIVATE_KEY_PATH` to your new PEM file.**

    ```bash
    # --- Token Service Details ---
    export TOKEN_ENDPOINT_URL="https://auth.example.com/oauth/token"
    export CLIENT_ID="your-unique-client-id"
    export RESOURCE_URI="https://cassandra-api.example.com"
    # THIS IS THE KEY CHANGE: Point to the extracted PEM file
    export CLIENT_PRIVATE_KEY_PATH="/path/to/your/client-private-key.pem"

    # --- Cassandra Details ---
    export CASSANDRA_HOST="cassandra.example.com"
    export CASSANDRA_DC="dc1"

    # --- Application Path ---
    export APP_JAR_PATH="/path/to/vendor-application.jar"
    ```

3.  **Execution:**
    *   Run the same `run-app.sh` script from the previous answer.

    ```bash
    ./run-app.sh
    ```

The script will now find the `client-private-key.pem` file, use `openssl` to sign the JWT, get the access token from your token service, and launch the vendor's Java application with the correct credentials. You have successfully bridged the gap between your JKS credential and the requirements of the script.
