// Hands the one-time code to the Android app. The code is useless without
// the verifier the app kept, so an app that grabs this link gets nothing.
const link = document.getElementById("open-app");
link.href = "lmnaide://oidc?code=" + encodeURIComponent(location.hash.slice(1));
history.replaceState(null, "", location.pathname);
location.replace(link.href);
