package dev.armenderoian.updateNotifier.client.gui;

import net.armenderoian.modpack.update.models.Update;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.MultilineText;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.awt.*;
import java.io.IOException;

@Environment(EnvType.CLIENT)
public class UpdateNotificationScreen extends Screen {

    private final Screen lastScreen;
    private final Update update;
    private int ticksUntilCanExit;

    private ButtonWidget exitButton;
    private ButtonWidget updateButton; // TODO: Can we run the update.exe file from here?

    private java.util.List<OrderedText> message;

    private int scrollOffset = 0;
    private int maxScroll = 0;

    public UpdateNotificationScreen(Screen lastScreen, Update update) {
        super(Text.of("Update Available"));
        this.lastScreen = lastScreen;
        this.update = update;
        this.ticksUntilCanExit = 100;
    }

    @Override
    protected void init() {
        super.init();
        this.exitButton = this.addDrawableChild(ButtonWidget.builder(Text.of("I'll Update Later"), (pressed) -> {
            MinecraftClient.getInstance().setScreen(this.lastScreen);
        }).dimensions(this.width / 2 + 5, this.height * 5 / 6, 150, 20).build());
        this.exitButton.active = false;

        this.updateButton = this.addDrawableChild(ButtonWidget.builder(
                Text.of("Update Now"), (pressed) -> {
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        try {
                            var file = FabricLoader.getInstance().getGameDir().resolve("mods").resolve("updater-1.0-SNAPSHOT.jar").toFile();

                            new ProcessBuilder("java", "-jar", file.getAbsolutePath())
                                    .directory(FabricLoader.getInstance().getGameDir().toFile())
                                    .inheritIO()
                                    .start();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }));

                    MinecraftClient.getInstance().scheduleStop();
                }).dimensions(this.width / 2 - 155, this.height * 5 / 6, 150, 20).build());
        this.updateButton.active = false;

        this.message = this.textRenderer.wrapLines(Text.of(updateToText(update)), this.width - 50);
        this.maxScroll = Math.max(0, this.message.size() * this.textRenderer.fontHeight - (this.height - 100));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, Color.WHITE.getRGB());

        int yStart = 55;
        int bottomY = this.height * 5 / 6 - 30; // 30px padding above buttons
        int availableHeight = bottomY - 55;    // 55 is top of text area
        int visibleLines = availableHeight / this.textRenderer.fontHeight;
        int startLine = scrollOffset / this.textRenderer.fontHeight;

        for (int i = 0; i < visibleLines && startLine + i < message.size(); i++) {
            int y = yStart + i * this.textRenderer.fontHeight;
            context.drawCenteredTextWithShadow(this.textRenderer, message.get(startLine + i), this.width / 2, y, 0xFFFFFF);
        }

        // Scrollbar rendering (optional)
        if (maxScroll > 0) {
            int scrollbarHeight = Math.max(20, (int)((float) availableHeight * visibleLines / message.size()));
            int scrollbarY = 55 + (int)((float)scrollOffset / maxScroll * (availableHeight - scrollbarHeight));
            context.fill(this.width - 15, 55, this.width - 10, this.height - 45, 0xFF202020);
            context.fill(this.width - 15, scrollbarY, this.width - 10, scrollbarY + scrollbarHeight, 0xFFFFFFFF);
        }

        this.updateButton.render(context, mouseX, mouseY, delta);
        this.exitButton.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollOffset -= (int) (verticalAmount * 10);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        if (--ticksUntilCanExit <= 0) {
            this.exitButton.active = true;
            this.updateButton.active = true;
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return ticksUntilCanExit <= 0;
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(lastScreen);
    }

    public String updateToText(Update update) {
        var meta = update.getMeta();
        var message = "Version: v" +
                meta.getVersion() +
                " is available!\n" +
                meta.getDescription() +
                "\n\nReleased: " +
                meta.getReleaseDate() +
                "\n\nChangelog:\n" +
                update.toChangelog();
        return message;
    }
}
