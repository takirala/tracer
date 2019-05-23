# tracer
A simple scala client that can make custom http queries - used to debug https://github.com/dcos/cosmos

```bash
# generate the jar
sbt assembly

# Run the jar - works with java 8
java -jar <jar-generated-above>
```

things jar can do :

**seturls** : url (or a csv of urls) to use.
**hit** : hit each endpoint with multiple clients and log the dns resolution records.
**dump** : print the DNS cache
**clear** : clear the DNS cache
**resolve** : resolve a hostname. optionally takes a custom DNS server.

Writes to `stderr`
