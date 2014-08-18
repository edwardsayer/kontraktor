package org.nustaq.kontraktor.remoting.http;

import org.nustaq.kontraktor.Actor;
import org.nustaq.kontraktor.Actors;
import org.nustaq.kontraktor.Callback;
import org.nustaq.kontraktor.annotations.CallerSideMethod;
import org.nustaq.kontraktor.util.RateMeasure;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.locks.LockSupport;

/**
 * Created by ruedi on 13.08.2014.
 *
 * Minimalistic Http server implementation. Only partial Http implementation necessary for Kontraktor Http Remoting.
 * Do NOT use in production. At least requires a reverse proxy like NGINX in front to have at least some dos protection.
 * Main purpose is development/light weight inhouse usage. Avoids too much dependencies of kontraktor-core.
 *
 * Its recommended to use KontraktorNettyServer (see github/kontraktor) for production.
 */
public class NioHttpServerImpl extends Actor<NioHttpServerImpl> implements NioHttpServer {

    ServerSocketChannel socket;
    Selector selector;
    SelectionKey serverkey;
    ByteBuffer buffer = ByteBuffer.allocate(1024*1024);
    int port;
    RequestProcessor processor;
    boolean shouldTerminate = false;
    long lastRequest;

    public void $init( int port, RequestProcessor processor) {
        Thread.currentThread().setName("NioHttp");
        this.port = port;
        this.processor = processor;
        try {
            selector = Selector.open();
            socket = ServerSocketChannel.open();
            socket.socket().bind(new java.net.InetSocketAddress(port));
            socket.configureBlocking(false);
            serverkey = socket.register(selector, SelectionKey.OP_ACCEPT);

            info("bound to port " + port);
        } catch (IOException e) {
            severe("could not bind to port" + port);
            e.printStackTrace();
        }
    }

    protected void severe(String s) {
        System.out.println("SEVERE:"+s);
    }

    protected void info(String s) {
        System.out.println("INFO:"+s);
    }

    public void $receive() {
        try {
            int keys = selector.selectNow();
            for (Iterator<SelectionKey> iterator = selector.selectedKeys().iterator(); iterator.hasNext(); ) {
                SelectionKey key = iterator.next();
                try {
                    if (key == serverkey) {
                        if (key.isAcceptable()) {
                            SocketChannel accept = socket.accept();
                            if (accept != null) {
                                accept.configureBlocking(false);
                                SelectionKey register = accept.register(selector, SelectionKey.OP_READ);
                                lastRequest = System.currentTimeMillis();
                            }
                        }
                    } else {
                        SocketChannel client = (SocketChannel) key.channel();
                        if (key.isReadable()) {
                            iterator.remove();
                            try {
                                service(key, client);
                            } catch (IOException ioe) {
                                key.cancel();
                                client.close();
                                throw ioe;
                            }
                        }
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        if ( ! shouldTerminate ) {
            if ( System.currentTimeMillis() - lastRequest > 100 ) {
                LockSupport.parkNanos(1000 * 1000); // latency ..
            }
            self().$receive();
        }
    }

    @Override @CallerSideMethod
    public Actor getServingActor() {
        return this;
    }

    public void $stopService() {
        shouldTerminate = true;
    }

    RateMeasure reqPerS = new RateMeasure("req/s", 5000);
    protected void service(final SelectionKey key, final SocketChannel client) throws IOException {
        if (!client.isOpen()) {
            key.cancel();
            client.close();
            return;
        }
        int bytesread = client.read(buffer);
        if (bytesread == -1) {
            key.cancel();
            client.close();
        } else {
            buffer.flip();
            reqPerS.count();
            KontraktorHttpRequest request = (KontraktorHttpRequest) key.attachment();
            if (request==null) {
                request = new KontraktorHttpRequestImpl(buffer, bytesread);
            }
            else {
                request.append(buffer, bytesread);
            }
            if ( ! request.isComplete() ) {
                key.attach(request);
            } else {
                key.attach(null);
                if (processor != null) {
                    try {
                        processor.processRequest(request,
                        (result, error) -> {

                             if (error == null || error == RequestProcessor.FINISHED) {
                                 try {
                                     if (result != null) {
                                         writeClient(client, ByteBuffer.wrap(result.toString().getBytes()));
                                     }
                                 } catch (Exception e) {
                                     e.printStackTrace();
                                 }
                             }
                             if (error != null) {
                                 try {
                                     if (error != RequestProcessor.FINISHED) {
                                         writeClient(client, ByteBuffer.wrap(error.toString().getBytes()));
                                     }
                                     key.cancel();
                                     client.close();
                                 } catch (IOException e) {
                                     e.printStackTrace();
                                 }
                             }
                        });
                    } catch (Exception ex) {
                        writeClient(client, ByteBuffer.wrap(ex.toString().getBytes()));
                        key.cancel();
                        client.close();
                    }
                } else {
                    key.cancel();
                    try {
                        client.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            buffer.clear();
        }
    }

    private void writeClient(SocketChannel client, ByteBuffer wrap) throws IOException {
        while ( wrap.remaining() > 0 )
            client.write(wrap);
    }

    static class SimpleProcessor implements RequestProcessor {

        @Override
        public void processRequest(KontraktorHttpRequest req, Callback response) {
            response.receiveResult( new RequestResponse( "HTTP/1.0 200 OK\n\n"+req.getText()), null);
            response.receiveResult(null,FINISHED);
        }
    }

    public static void main( String arg[] ) throws InterruptedException {
        NioHttpServerImpl server = Actors.AsActor(NioHttpServerImpl.class);

        server.$init(9999, new SimpleProcessor());
        server.$receive();
    }

}

