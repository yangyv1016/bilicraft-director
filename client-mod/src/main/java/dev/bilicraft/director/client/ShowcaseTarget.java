package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Message;

public sealed interface ShowcaseTarget permits ShowcaseTarget.Player, ShowcaseTarget.Scene {
    String id();

    String name();

    String label();

    record Player(Message.PlayerView view) implements ShowcaseTarget {
        @Override
        public String id() {
            return "player:" + view.id();
        }

        @Override
        public String name() {
            return view.name();
        }

        @Override
        public String label() {
            return "[玩家] " + view.name();
        }
    }

    record Scene(Message.SceneView view) implements ShowcaseTarget {
        @Override
        public String id() {
            return "scene:" + view.world() + ":" + view.id();
        }

        @Override
        public String name() {
            return view.name();
        }

        @Override
        public String label() {
            return switch (view.kind()) {
                case VIEWPOINT -> "[观景点] " + view.name();
                case AREA -> "[巡游区域] " + view.name();
            };
        }
    }
}
