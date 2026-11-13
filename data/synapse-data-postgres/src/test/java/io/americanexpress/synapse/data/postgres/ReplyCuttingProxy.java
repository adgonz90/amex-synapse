/*
 * Copyright 2026 American Express Travel Related Services Company, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.americanexpress.synapse.data.postgres;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A TCP proxy in front of a database, for tests: it forwards both ways until {@link #cutReplies()}, then it drops the
 * next reply that comes back from the database and closes both sides. A commit sent after the cut reaches the
 * database, which applies it, but the client never sees the answer, as when a connection breaks after the COMMIT.
 */
final class ReplyCuttingProxy implements AutoCloseable {

    private final ServerSocket server;

    private final String host;

    private final int port;

    private final ExecutorService pumps = Executors.newVirtualThreadPerTaskExecutor();

    private volatile boolean cut;

    /**
     * Starts the proxy on a free local port.
     *
     * @param host the database's host
     * @param port the database's port
     * @throws IOException when the proxy can't listen
     */
    ReplyCuttingProxy(String host, int port) throws IOException {
        this.host = host;
        this.port = port;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pumps.execute(this::accept);
    }

    /** The port clients connect to. */
    int port() {
        return server.getLocalPort();
    }

    /** From now on, the next reply from the database is dropped and the connection closed. */
    void cutReplies() {
        cut = true;
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Socket database = new Socket(host, port);
                pumps.execute(() -> pump(client, database, false));
                pumps.execute(() -> pump(database, client, true));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void pump(Socket from, Socket to, boolean replies) {
        byte[] buffer = new byte[8192];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
                if (replies && cut) {
                    break;
                }
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException closed) {
            // The other pump closed the sockets.
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing anyway.
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        pumps.shutdownNow();
    }
}
