import re

with open("curl.txt", "r", encoding="utf-8") as f:
    curl = f.read()

cookie = re.search(r"-b '([^']+)'", curl)
authuser = re.search(r"x-goog-authuser: ([^']+)", curl)
visitor = re.search(r"x-goog-visitor-id: ([^']+)", curl)
authorization = re.search(r"authorization: ([^']+)", curl)
user_agent = re.search(r"user-agent: ([^']+)", curl)

print(cookie.group(1))
print(authuser.group(1))