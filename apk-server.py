import http.server
import socketserver
import os

PORT = 8765
FILENAME = "MC3D-Weaver.apk"
SIZE = os.path.getsize(FILENAME)


class Handler(http.server.SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def end_headers(self):
        self.send_header("Content-Disposition",
                         f'attachment; filename="{FILENAME}"')
        self.send_header("Accept-Ranges", "none")
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def translate_path(self, path):
        # serve only the APK, ignore everything else
        return os.path.join(os.getcwd(), FILENAME)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    with Server(("0.0.0.0", PORT), Handler) as httpd:
        print(f"serving {FILENAME} ({SIZE} bytes) on port {PORT}", flush=True)
        httpd.serve_forever()
