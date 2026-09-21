package com.kadamitas.fabricatedbackpacks.client.render;

/** Per-extraction state; the render layer never reads mutable live equipment. */
public interface BackpackAvatarState {
    BackpackVisualState fabricatedBackpacks$visual();
    void fabricatedBackpacks$visual(BackpackVisualState visual);
    BackpackDisplayState fabricatedBackpacks$display();
    void fabricatedBackpacks$display(BackpackDisplayState display);
}
