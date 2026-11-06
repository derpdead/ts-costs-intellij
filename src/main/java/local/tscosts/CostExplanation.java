package local.tscosts;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class CostExplanation {
    static String finding(ProfileData.Hint hint) {
        var analysis = hint.analysis;
        if (analysis == null) return "<p><b>Earlier recording</b> · Record again for operation and type evidence.</p>";
        String first = analysis.actions == null || analysis.actions.isEmpty() ? ""
            : "<p><b>Investigate first:</b> " + text(analysis.actions.getFirst().title) + ". See What to try below.</p>";
        return "<p><b>" + text(analysis.attribution) + "</b><br>" + text(analysis.finding) + "</p>" + first;
    }

    static String details(ProfileData.Hint hint, String interpretation) {
        StringBuilder body = new StringBuilder();
        var analysis = hint.analysis;
        if (analysis != null) {
            suggestions(body, analysis);
            body.append("<h3>Recorded operations</h3>");
            if (analysis.operations == null || analysis.operations.isEmpty()) {
                body.append("<p>No nested operations in this recording. The generated code explains the scope, but does not measure the helpers separately.</p>");
            } else {
                body.append("<table width='100%' cellpadding='4' cellspacing='0'><tr><th align='left'>Operation</th><th align='right'>Elapsed</th></tr>");
                for (var operation : analysis.operations) {
                    body.append("<tr><td>").append("&nbsp;".repeat(Math.max(0, Math.min(3, operation.depth)) * 3))
                        .append("<b>").append(text(operationName(operation.event))).append("</b>")
                        .append("<br><small>").append(text(operation.event)).append("</small>");
                    if (operation.sourceType != null || operation.targetType != null) {
                        body.append("<br>From ").append(typeLabel(operation.sourceType))
                            .append("<br>To ").append(typeLabel(operation.targetType));
                    }
                    body.append("</td><td align='right' valign='top'>").append(time(operation.ms)).append("</td></tr>");
                }
                body.append("</table><p>Inclusive spans; nested rows overlap. Showing ")
                    .append(analysis.operations.size()).append(" of ").append(analysis.totalOperations).append(" recorded operations.</p>");
                Map<Integer, ProfileData.TypeEvidence> types = new LinkedHashMap<>();
                for (var operation : analysis.operations) {
                    if (operation.sourceType != null) types.put(operation.sourceType.id, operation.sourceType);
                    if (operation.targetType != null) types.put(operation.targetType.id, operation.targetType);
                }
                if (!types.isEmpty()) {
                    body.append("<h3>Types in these comparisons</h3>");
                    int shown = 0;
                    for (var type : types.values()) {
                        if (shown++ == 6) break;
                        body.append("<p><b>").append(text(type.label)).append("</b> · #").append(type.id);
                        if (!type.resolved) body.append("<br>No matching entry in the type dump.");
                        else {
                            body.append("<br>").append(text(type.kind));
                            if (type.memberCount > 0) body.append(" · ").append(type.memberCount)
                                .append("union".equals(type.kind) || "intersection".equals(type.kind) ? " constituents" : " type arguments");
                            if (type.members != null && !type.members.isEmpty()) {
                                body.append("<br>").append(text(String.join(", ", type.members)));
                                if (type.omittedMembers > 0) body.append(" … +").append(type.omittedMembers).append(" more");
                            }
                            if (type.declaration != null) body.append("<br>Declared at ").append(text(shortLocation(type.declaration)));
                        }
                        body.append("</p>");
                    }
                    if (types.size() > 6) body.append("<p>").append(types.size() - 6).append(" additional types appear in the operation rows.</p>");
                }
            }
            if (analysis.helpers != null && !analysis.helpers.isEmpty()) {
                body.append("<h3>Generated helpers in this excerpt</h3>");
                for (String helper : analysis.helpers) body.append("<p><b>").append(text(helper)).append("</b><br>")
                    .append(text(helperRole(helper))).append("</p>");
                body.append("<p>These helpers are present in the captured code. No individual helper timings were recorded.</p>");
            }
            body.append("<h3>Verify the change</h3><p>").append(text(analysis.verification)).append("</p>");
        }
        body.append("<h3>Measurement details</h3><p>").append(text(hint.event)).append(" · ").append(text(hint.mapping))
            .append("<br>Whole span: ").append(time(hint.ms)).append("<br>Recorded direct-child coverage: ").append(time(hint.childMs))
            .append("<br>Outside recorded children: ").append(time(hint.unattributedMs)).append("</p>");
        if (analysis == null) body.append("<p>").append(text(hint.children)).append("</p>");
        body.append("<p>").append(text(hint.evidence)).append("</p><h3>Limits of attribution</h3><p>")
            .append(text(hint.limitation == null ? interpretation : hint.limitation))
            .append(" Time outside recorded children is not intrinsic self CPU. No per-line memory measurement is available.</p>");
        if (hint.sourceTruncated || hint.generatedTruncated) body.append("<p>Captured preview is truncated: ")
            .append(hint.sourceTruncated ? "source (500 characters)" : "")
            .append(hint.sourceTruncated && hint.generatedTruncated ? "; " : "")
            .append(hint.generatedTruncated ? "generated code (2400 characters)" : "")
            .append(". Expand and Copy use this same recorded excerpt.</p>");
        return body.toString();
    }

    private static void suggestions(StringBuilder body, ProfileData.Analysis analysis) {
        body.append("<h3>What to try</h3><p>Suggestions to validate · no measured speedup</p>");
        if (analysis.actions != null) for (var action : analysis.actions) {
            body.append("<p><b>").append(text(action.title)).append("</b><br>")
                .append(text(action.change)).append("</p><p><i>Why here:</i> ").append(text(action.basis)).append("</p>");
            if (action.reference != null && action.reference.startsWith("https://github.com/microsoft/TypeScript/wiki/Performance#")) {
                body.append("<p><a href='").append(action.reference.replace("'", "%27"))
                    .append("'>TypeScript performance guide</a></p>");
            }
        }
    }

    private static String helperRole(String helper) {
        return switch (helper) {
            case "__VLS_ResolveEvent" -> "Resolve the component event signature used by this generated handler.";
            case "__VLS_EventArgs" -> "Derive the argument types for the component event handler.";
            case "__VLS_FunctionalComponentProps" -> "Resolve the component props used by the generated invocation.";
            case "Partial<" -> "Make properties optional in the generated type.";
            default -> "Generated type helper.";
        };
    }

    private static String typeLabel(ProfileData.TypeEvidence type) {
        return type == null ? "not recorded" : text(type.label) + " (#" + type.id + ")";
    }

    private static String operationName(String event) {
        if (event == null) return "Compiler operation";
        return switch (event) {
            case "structuredTypeRelatedTo" -> "Compare structured types";
            case "checkExpression" -> "Check expression";
            case "checkVariableDeclaration" -> "Check declaration";
            case "checkDeferredNode" -> "Check deferred node";
            case "getVariancesWorker" -> "Determine generic variance";
            default -> event;
        };
    }

    private static String shortLocation(String location) {
        return location.replace('\\', '/').substring(location.replace('\\', '/').lastIndexOf('/') + 1);
    }

    private static String time(double value) {
        return value >= 1000 ? String.format(Locale.ROOT, "%.2f s", value / 1000)
            : String.format(Locale.ROOT, value > 0 && value < 1 ? "%.2f ms" : "%.1f ms", value);
    }

    private static String text(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\n", "<br>");
    }
}
