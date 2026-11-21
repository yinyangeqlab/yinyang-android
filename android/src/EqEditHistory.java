package com.yinyangeq.qudelixprobe;

import java.util.ArrayDeque;
import java.util.Base64;
import java.util.stream.Collectors;

/** Each entry is one committed editor action, including an import or a reset. */
final class EqEditHistory {
    private static final int LIMIT = 40;
    private final ArrayDeque<Peq> undo = new ArrayDeque<>();
    private final ArrayDeque<Peq> redo = new ArrayDeque<>();

    boolean canUndo() { return !undo.isEmpty(); }
    boolean canRedo() { return !redo.isEmpty(); }
    Peq first() { return undo.peekFirst(); }

    void record(Peq before, Peq after) {
        if (before.same(after)) return;
        if (undo.size() == LIMIT) undo.removeFirst();
        undo.addLast(before.copy());
        redo.clear();
    }

    Peq undo(Peq current) { return move(current, undo, redo); }
    Peq redo(Peq current) { return move(current, redo, undo); }

    private static Peq move(Peq current, ArrayDeque<Peq> from, ArrayDeque<Peq> to) {
        if (from.isEmpty()) return current;
        to.addLast(current.copy());
        return from.removeLast();
    }

    void clear() { undo.clear(); redo.clear(); }

    String undoBlocks() { return encode(undo); }
    String redoBlocks() { return encode(redo); }

    void reload(int group, String undoBlocks, String redoBlocks) {
        clear();
        try {
            decode(undo, undoBlocks, group);
            decode(redo, redoBlocks, group);
        } catch (IllegalArgumentException invalid) {
            clear();
        }
    }

    private static String encode(ArrayDeque<Peq> values) {
        return values.stream().map(p -> Base64.getEncoder().encodeToString(p.bytes))
                .collect(Collectors.joining(","));
    }

    private static void decode(ArrayDeque<Peq> list, String data, int group) {
        if (data.isEmpty()) return;
        for (String block : data.split(",", LIMIT))
            list.addLast(new Peq(group, Base64.getDecoder().decode(block)));
    }
}
