package dev.bilicraft.ai.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

final class AiConfigScreen extends Screen {
    private final Screen parent;
    private final AiSettings settings = BiliCraftAiClient.getInstance().aiSettings;
    private final Map<AiSettings.Provider, AiSettings.Profile> drafts = new EnumMap<>(AiSettings.Provider.class);
    private AiSettings.Provider selected;
    private Text status;
    private boolean dirty;
    private boolean error;
    private boolean enabled;
    private boolean testing;
    private int left;
    private int titleY;
    private int addressY;
    private int modelY;
    private int keyY;
    private int statusY;
    private int footerY;
    private int rowHeight;

    AiConfigScreen(Screen parent) {
        super(Text.translatable("bilicraftai.ai.title"));
        this.parent = parent;
        selected = settings.selected;
        enabled = settings.enabled;
        for (AiSettings.Provider provider : AiSettings.Provider.values()) {
            AiSettings.Profile profile = settings.profiles.get(provider);
            drafts.put(provider, new AiSettings.Profile(profile.baseUrl, profile.model, profile.apiKey));
        }
        status = Text.translatable(settings.loadFailed ? "bilicraftai.ai.load_failed"
                : !settings.saved ? "bilicraftai.ai.not_saved"
                : drafts.get(selected).complete() ? "bilicraftai.ai.saved_unverified" : "bilicraftai.ai.saved_incomplete");
        error = settings.loadFailed;
    }

    @Override
    protected void init() {
        int gap = height < 220 ? 4 : 8;
        rowHeight = height < 180 ? 14 : height < 220 ? 16 : 20;
        int panelWidth = Math.min(320, width - 16);
        left = (width - panelWidth) / 2;
        int fieldX = left + 58;
        int fieldWidth = panelWidth - 58;
        titleY = Math.max(4, (height - (rowHeight * 5 + gap * 5 + 38)) / 2);
        int y = titleY + 26;
        addDrawableChild(ButtonWidget.builder(Text.translatable("bilicraftai.ai.provider",
                Text.translatable(selected.translationKey)), button -> {
            selected = selected == AiSettings.Provider.DEEPSEEK ? AiSettings.Provider.OPENAI : AiSettings.Provider.DEEPSEEK;
            dirty = true;
            error = false;
            status = Text.translatable("bilicraftai.ai.unsaved");
            clearChildren();
            init();
        }).dimensions(left, y, panelWidth * 2 / 3 - gap, rowHeight).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable(enabled ? "bilicraftai.ai.enabled" : "bilicraftai.ai.disabled"), button -> {
            enabled = !enabled;
            dirty = true;
            testing = false;
            error = false;
            status = Text.translatable("bilicraftai.ai.unsaved");
            clearChildren();
            init();
        }).dimensions(left + panelWidth * 2 / 3, y, panelWidth / 3, rowHeight).build());
        AiSettings.Profile draft = drafts.get(selected);
        y += rowHeight + gap;
        addressY = y;
        TextFieldWidget address = addDrawableChild(new TextFieldWidget(textRenderer, fieldX, y, fieldWidth, rowHeight,
                Text.translatable("bilicraftai.ai.address")));
        address.setMaxLength(1024);
        address.setText(draft.baseUrl);
        address.setChangedListener(value -> {
            draft.baseUrl = value;
            dirty = true;
            error = false;
            status = Text.translatable("bilicraftai.ai.unsaved");
        });
        y += rowHeight + gap;
        modelY = y;
        TextFieldWidget model = addDrawableChild(new TextFieldWidget(textRenderer, fieldX, y, fieldWidth, rowHeight,
                Text.translatable("bilicraftai.ai.model")));
        model.setMaxLength(256);
        model.setPlaceholder(Text.translatable("bilicraftai.ai.model_placeholder"));
        model.setText(draft.model);
        model.setChangedListener(value -> {
            draft.model = value;
            dirty = true;
            error = false;
            status = Text.translatable("bilicraftai.ai.unsaved");
        });
        y += rowHeight + gap;
        keyY = y;
        SecretField key = addDrawableChild(new SecretField(textRenderer, fieldX, y, fieldWidth, rowHeight));
        key.setMaxLength(4096);
        key.setPlaceholder(Text.translatable("bilicraftai.ai.key_placeholder"));
        key.setText(draft.apiKey);
        key.setChangedListener(value -> {
            draft.apiKey = value;
            dirty = true;
            error = false;
            status = Text.translatable("bilicraftai.ai.unsaved");
        });
        statusY = y + rowHeight + gap;
        y = statusY + 14;
        int buttonWidth = (panelWidth - gap * 2) / 3;
        addDrawableChild(ButtonWidget.builder(Text.translatable("bilicraftai.ai.save"), button -> {
            if (drafts.values().stream().anyMatch(profile -> !profile.apiKey.isEmpty()
                    && !profile.apiKey.chars().allMatch(value -> value >= 33 && value <= 126))) {
                error = true;
                status = Text.translatable("bilicraftai.ai.invalid_key");
                return;
            }
            try {
                settings.save(selected, drafts, enabled);
                dirty = false;
                testing = false;
                error = false;
                status = Text.translatable(draft.complete() ? "bilicraftai.ai.saved_unverified" : "bilicraftai.ai.saved_incomplete");
            } catch (IllegalArgumentException exception) {
                error = true;
                status = Text.translatable("bilicraftai.ai.invalid_address");
            } catch (IOException exception) {
                error = true;
                status = Text.translatable("bilicraftai.ai.save_failed");
            }
        }).dimensions(left, y, buttonWidth, rowHeight).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("bilicraftai.ai.test"), button -> {
            BiliCraftAiClient director = BiliCraftAiClient.getInstance();
            if (dirty) {
                error = true;
                status = Text.translatable("bilicraftai.ai.save_before_test");
            } else {
                testing = director.testAiConnection();
                error = !testing;
                status = Text.literal(director.status());
            }
        }).dimensions(left + buttonWidth + gap, y, buttonWidth, rowHeight).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("bilicraftai.ai.back"), button -> close())
                .dimensions(left + (buttonWidth + gap) * 2, y, buttonWidth, rowHeight).build());
        footerY = y + rowHeight + gap;
        setInitialFocus(address);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xffffffff);
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("bilicraftai.ai.configuration_only"),
                width / 2, titleY + 12, 0xffffcc66);
        int textOffset = (rowHeight - 9) / 2;
        context.drawTextWithShadow(textRenderer, Text.translatable("bilicraftai.ai.address"), left, addressY + textOffset, 0xffffffff);
        context.drawTextWithShadow(textRenderer, Text.translatable("bilicraftai.ai.model"), left, modelY + textOffset, 0xffffffff);
        context.drawTextWithShadow(textRenderer, Text.literal("API Key"), left, keyY + textOffset, 0xffffffff);
        String displayedStatus = testing ? BiliCraftAiClient.getInstance().aiConnectionStatus() : status.getString();
        context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(displayedStatus, width - 16),
                width / 2, statusY, error ? 0xffff7777 : dirty ? 0xffffcc66 : 0xffb7dfb7);
        if (footerY + 9 <= height - 2) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("bilicraftai.ai.local_only"),
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

    private static final class SecretField extends TextFieldWidget {
        SecretField(TextRenderer renderer, int x, int y, int width, int height) {
            super(renderer, x, y, width, height, Text.literal("API Key"));
            addFormatter((text, index) -> OrderedText.styledForwardsVisitedString("*".repeat(text.length()), Style.EMPTY));
        }

        @Override
        public boolean keyPressed(KeyInput input) {
            if (isFocused() && (input.isCopy() || input.isCut())) {
                return true;
            }
            return super.keyPressed(input);
        }

        @Override
        public String getSelectedText() {
            return "*".repeat(super.getSelectedText().length());
        }

        @Override
        protected MutableText getNarrationMessage() {
            return Text.translatable("bilicraftai.ai.key_narration");
        }
    }
}
