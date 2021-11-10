package com.geekbrains.client;

import com.geekbrains.common.Command;
import com.geekbrains.common.CommandType;
import com.geekbrains.common.commands.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.*;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class Network {

    private static final int SERVER_PORT = 8189;
    private static final String SERVER_HOST = "localhost";
    private static Network INSTANCE;
    private final String host;
    private final int port;
    private SocketChannel socketChannel;
    private boolean isConnect;

    public static Network getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new Network();
        }
        return INSTANCE;
    }

    private Network(String host, int port) {
        this.host = host;
        this.port = port;
    }

    private Network() {
        this(SERVER_HOST, SERVER_PORT);
    }

    public void connect() {
        Thread thread = new Thread(() -> {
            EventLoopGroup workerGroup = new NioEventLoopGroup();
            try {
                Bootstrap bootstrap = new Bootstrap();
                bootstrap.group(workerGroup)
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                socketChannel = ch;
                                ch.pipeline().addLast(
                                        new ObjectDecoder(ClassResolvers.cacheDisabled(null)),
                                        new ObjectEncoder(),
                                        new SimpleChannelInboundHandler<Command>() {
                                            @Override
                                            protected void channelRead0(ChannelHandlerContext ctx, Command msg) {
                                                readCommand(msg);
                                            }

                                            @Override
                                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                                Platform.runLater(() -> {
                                                    try {
                                                        App.INSTANCE.getMainController().connectLost();
                                                    } catch (IOException | InterruptedException e) {
                                                        e.printStackTrace();
                                                    }
                                                });
                                                isConnect = false;
                                            }
                                        }
                                );
                            }
                        });
                ChannelFuture future = bootstrap.connect(host, port).sync();
                log.debug("connect");
                isConnect = true;
                future.channel().closeFuture().sync();
            } catch (Exception e) {
                isConnect = false;
                log.debug("Network error");
                e.printStackTrace();
                Platform.runLater(() -> App.INSTANCE.getMainController().showAlert("Network error", Alert.AlertType.ERROR));
            } finally {
                workerGroup.shutdownGracefully();
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    public void readCommand(Command command) {
        if (command.getType() == CommandType.INFO) {
            InfoCommandData data = (InfoCommandData) command.getData();
            log.debug(data.getMessage());
            Platform.runLater(() -> App.INSTANCE.getMainController().showAlert(data.getMessage(), Alert.AlertType.INFORMATION));
        } else if (command.getType() == CommandType.ERROR) {
            ErrorCommandData data = (ErrorCommandData) command.getData();
            log.debug(data.getErrorMessage());
            Platform.runLater(() -> App.INSTANCE.getMainController().showAlert(data.getErrorMessage(), Alert.AlertType.ERROR));
        } else if (command.getType() == CommandType.AUTH_OK) {
            AuthOkCommandData data = (AuthOkCommandData) command.getData();
            log.debug("Auth OK: " + data.getUsername());
            String username = data.getUsername();
            Platform.runLater(() -> App.INSTANCE.switchToMainWindow(username));
            Platform.runLater(() -> App.INSTANCE.getMainController().connectLabel.setText("SERVER: ON"));
            Platform.runLater(() -> App.INSTANCE.getMainController().showAlert("You are signed in as " + username, Alert.AlertType.INFORMATION));
        } else if (command.getType() == CommandType.UPDATE_FILE_LIST) {
            UpdateFileListCommandData data = (UpdateFileListCommandData) command.getData();
            Platform.runLater(() -> App.INSTANCE.getMainController().updateServerListView(data.getFiles()));
        } else if (command.getType() == CommandType.FILE_INFO) {
            FileInfoCommandData data = (FileInfoCommandData) command.getData();
            Platform.runLater(() -> {
                try {
                    App.INSTANCE.getMainController().download(data.getName(), data.getSize(), data.getBytes(), data.isStart(), data.getEndPos());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }
    }

    private void sendCommand(Command command) {
        socketChannel.writeAndFlush(command);
    }

    public void sendFile(String fileName, long fileSize, byte[] bytes, boolean isStart, int endPos) {
        sendCommand(Command.fileInfoCommand(fileName, fileSize, bytes, isStart, endPos));
        // Без следующей задержки файл на сервере не открывается, хотя размер результата и исходника
        // совпадают байт в байт. При передаче одним куском всё хорошо.
        // А вот с сервера на клиент всё передаётся чётко без всякой задержки.
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void sendAuthMessage(String login, char[] password) {
        sendCommand(Command.authCommand(login, password));
    }

    public void sendRegMessage(String username, String login, char[] password) {
        sendCommand(Command.regCommand(username, login, password));
    }

    public void sendFileRequest(String fileNameToDownload) {
        sendCommand(Command.fileRequestCommand(fileNameToDownload));
    }

    public void sendUpRequest() throws IOException {
        sendCommand(Command.upRequestCommand());
    }

    public void sendDeleteRequest(String fileName) throws IOException {
        sendCommand(Command.deleteRequestCommand(fileName));
    }

    public void sendCreateDirRequest(String name) throws IOException {
        sendCommand(Command.createDirRequestCommand(name));
    }

    public void sendRenameRequest(String file, String newName) {
        sendCommand(Command.renameRequestCommand(file, newName));
    }

    public void sendChangeUsername(String newName) {
        sendCommand(Command.changeUsernameCommand(newName));
    }

    public void close() {
        socketChannel.close();
    }

    public boolean isConnect() {
        return isConnect;
    }
}