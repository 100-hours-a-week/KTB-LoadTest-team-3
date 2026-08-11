package com.ktb.chatapp.util;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.springframework.util.Assert;

public class BannedWordChecker {

    private static final class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private Node fail;
        private boolean output;
    }

    private final Node root = new Node();

    public BannedWordChecker(Set<String> bannedWords) {
        Set<String> normalized = normalize(bannedWords);
        Assert.notEmpty(normalized, "Banned words set must not be empty");
        normalized.forEach(this::insert);
        buildFailureLinks();
    }

    private static Set<String> normalize(Set<String> bannedWords) {
        return bannedWords.stream()
                .filter(word -> word != null && !word.isBlank())
                .map(word -> word.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void insert(String word) {
        Node current = root;
        for (int i = 0; i < word.length(); i++) {
            current = current.children.computeIfAbsent(word.charAt(i), c -> new Node());
        }
        current.output = true;
    }

    private void buildFailureLinks() {
        Queue<Node> queue = new ArrayDeque<>();
        for (Node child : root.children.values()) {
            child.fail = root;
            queue.add(child);
        }

        while (!queue.isEmpty()) {
            Node current = queue.poll();
            for (Map.Entry<Character, Node> entry : current.children.entrySet()) {
                char c = entry.getKey();
                Node child = entry.getValue();

                Node failCandidate = current.fail;
                while (failCandidate != null && !failCandidate.children.containsKey(c)) {
                    failCandidate = failCandidate.fail;
                }
                child.fail = (failCandidate == null) ? root : failCandidate.children.get(c);
                child.output |= child.fail.output;

                queue.add(child);
            }
        }
    }

    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        Node current = root;
        for (int i = 0; i < normalizedMessage.length(); i++) {
            char c = normalizedMessage.charAt(i);
            while (current != root && !current.children.containsKey(c)) {
                current = current.fail;
            }
            current = current.children.getOrDefault(c, root);
            if (current.output) {
                return true;
            }
        }
        return false;
    }
}
