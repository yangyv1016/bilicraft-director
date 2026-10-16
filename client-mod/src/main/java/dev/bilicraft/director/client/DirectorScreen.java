package dev.bilicraft.director.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

public final class DirectorScreen extends Screen {
    private final Screen parent;
    private final BiliCraftDirectorClient director = BiliCraftDirectorClient.getInstance();
    private int selected;
    private ButtonWidget toggle;
    private int titleY;
    private int candidateY;
    private int radiusY;
    private int speedY;
    private int footerY;

    public DirectorScreen(Screen parent) {
        super(Text.literal("BiliCraft 导播"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int gap = height < 180 ? 3 : height < 220 ? 4 : 8;
        int rowHeight = height < 180 ? 12 : height < 220 ? 16 : 20;
        int panelWidth = Math.min(320, width - 16);
        int x = (width - panelWidth) / 2;
        int halfWidth = (panelWidth - gap) / 2;
        titleY = Math.max(4, (height - (rowHeight * 6 + gap * 6 + 47)) / 2);
        int y = titleY + 26;
        toggle = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            if (director.isRunning()) {
                director.stop("已从菜单停止");
            } else {
                director.start();
            }
        }).dimensions(x, y, halfWidth, rowHeight).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("刷新展示目标"), button -> director.requestSnapshot(0))
                .dimensions(x + halfWidth + gap, y, halfWidth, rowHeight).build());
        candidateY = y + rowHeight + gap;
        y = candidateY + 12;
        addDrawableChild(ButtonWidget.builder(Text.literal("上一个"), button -> selected--)
                .dimensions(x, y, 60, rowHeight).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("展示所选目标"), button -> {
            List<ShowcaseTarget> targets = director.targets();
            if (!targets.isEmpty()) {
                director.showTarget(targets.get(Math.floorMod(selected, targets.size())));
            }
        }).dimensions(x + 60 + gap, y, panelWidth - 120 - gap * 2, rowHeight).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("下一个"), button -> selected++)
                .dimensions(x + panelWidth - 60, y, 60, rowHeight).build());
        y += rowHeight + gap;
        radiusY = y + (rowHeight - 9) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("半径 −"), button -> {
            director.settings.radius = Math.max(6, director.settings.radius - 1);
            director.saveSettings();
        }).dimensions(x, y, 60, rowHeight).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("半径 ＋"), button -> {
            director.settings.radius = Math.min(256, director.settings.radius + 1);
            director.saveSettings();
        }).dimensions(x + panelWidth - 60, y, 60, rowHeight).build());
        y += rowHeight + gap;
        speedY = y + (rowHeight - 9) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("速度 −"), button -> {
            director.settings.speed = Math.max(0.5, director.settings.speed - 0.5);
            director.saveSettings();
        }).dimensions(x, y, 60, rowHeight).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("速度 ＋"), button -> {
            director.settings.speed = Math.min(5, director.settings.speed + 0.5);
            director.saveSettings();
        }).dimensions(x + panelWidth - 60, y, 60, rowHeight).build());
        y += rowHeight + gap;
        addDrawableChild(ButtonWidget.builder(Text.translatable("bilicraftdirector.ai.open"),
                button -> client.setScreen(new AiConfigScreen(this)))
                .dimensions(x, y, halfWidth, rowHeight).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("打开本地中控"), button -> director.openLocalControl())
                .dimensions(x + halfWidth + gap, y, halfWidth, rowHeight).build());
        y += rowHeight + gap;
        addDrawableChild(ButtonWidget.builder(Text.literal("返回"), button -> close())
                .dimensions(x, y, panelWidth, rowHeight).build());
        footerY = y + rowHeight + gap;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        toggle.setMessage(Text.literal(director.isRunning() ? "停止导播" : "启动导播"));
        super.render(context, mouseX, mouseY, deltaTicks);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xffffffff);
        String status = textRenderer.trimToWidth(director.status(), width - 30);
        context.drawCenteredTextWithShadow(textRenderer, status, width / 2, titleY + 12, 0xffb7dfb7);
        List<ShowcaseTarget> targets = director.targets();
        String candidate = "暂无目标；刷新玩家、观景点与巡游区域";
        if (!targets.isEmpty()) {
            selected = Math.floorMod(selected, targets.size());
            candidate = targets.get(selected).label() + "  " + (selected + 1) + "/" + targets.size();
        }
        context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(candidate, width - 16),
                width / 2, candidateY, 0xffffffff);
        context.drawCenteredTextWithShadow(textRenderer,
                String.format(Locale.ROOT, "%.0f 格", director.settings.radius), width / 2, radiusY, 0xffffffff);
        context.drawCenteredTextWithShadow(textRenderer,
                String.format(Locale.ROOT, "%.1f 格 / 秒", director.settings.speed), width / 2, speedY, 0xffffffff);
        if (footerY + 9 <= height - 2) {
            context.drawCenteredTextWithShadow(textRenderer, "F8 启停 · F9 菜单 · ← 素材前选 · → 跳过队列",
                    width / 2, footerY, 0xffaaaaaa);
        }
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
