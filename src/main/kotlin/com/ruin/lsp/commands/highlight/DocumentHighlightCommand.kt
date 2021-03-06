package com.ruin.lsp.commands.highlight

import com.github.kittinunf.result.Result
import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.featureStatistics.FeatureUsageTracker
import com.intellij.find.FindManager
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.find.impl.FindManagerImpl
import com.intellij.idea.ActionsBundle
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageTargetUtil
import com.intellij.util.containers.ContainerUtil
import com.ruin.lsp.util.findTargetElement
import com.ruin.lsp.util.withEditor
import com.ruin.lsp.commands.Command
import com.ruin.lsp.values.*

class DocumentHighlightCommand(val textDocumentIdentifier: TextDocumentIdentifier,
                               val position: Position) : Command<List<DocumentHighlight>> {
    override fun execute(project: Project, file: PsiFile): Result<List<DocumentHighlight>, Exception> {

        val ref: Ref<List<DocumentHighlight>> = Ref()
        withEditor(this, file, position) { editor ->
            try {
                ref.set(findHighlights(project, editor, file))
            } catch (ex: IndexNotReadyException) {
                DumbService.getInstance(project).showDumbModeNotification(ActionsBundle.message("action.HighlightUsagesInFile.not.ready"))
            }
        }

        return Result.of(ref.get())
    }
}


fun offsetToPosition(editor: Editor, offset: Int): Position {
    val logicalPos = editor.offsetToLogicalPosition(offset)
    return Position(logicalPos.line, logicalPos.column)
}

fun textRangeToRange(editor: Editor, range: TextRange) =
    Range(
        offsetToPosition(editor, range.startOffset),
        offsetToPosition(editor, range.endOffset)
    )


private fun findHighlights(project: Project, editor: Editor, file: PsiFile): List<DocumentHighlight>? {
    val handler = HighlightUsagesHandler.createCustomHandler(editor, file)

    return if (handler != null) {
        getHighlightsFromHandler(handler, editor)
    } else {
        getHighlightsFromUsages(project, editor, file)
    }
}

private fun getHighlightsFromHandler(handler: HighlightUsagesHandlerBase<PsiElement>,
                                     editor: Editor): List<DocumentHighlight> {
    val featureId = handler.featureId

    if (featureId != null) {
        FeatureUsageTracker.getInstance().triggerFeatureUsed(featureId)
    }

    // NOTE: Not able to use handler.selectTargets()
    handler.computeUsages(handler.targets)

    val reads  = textRangesToHighlights(handler.readUsages, editor, DocumentHighlightKind.READ)
    val writes = textRangesToHighlights(handler.writeUsages, editor, DocumentHighlightKind.WRITE)

    return reads.plus(writes)
}

private fun textRangesToHighlights(usages: List<TextRange>, editor: Editor, kind: Int): List<DocumentHighlight> =
    usages.map {
        val range = textRangeToRange(editor, it)
        DocumentHighlight(range, kind)
    }

private fun getHighlightsFromUsages(project: Project, editor: Editor, file: PsiFile): List<DocumentHighlight>? {
    val ref: Ref<List<DocumentHighlight>> = Ref()
    DumbService.getInstance(project).withAlternativeResolveEnabled {
        val usageTargets = getUsageTargets(editor, file)
        val result = usageTargets?.mapNotNull {
            extractDocumentHighlightFromRaw(project, file, editor, it)
        }?.flatten() ?: listOf()

        ref.set(result)
    }

    return ref.get()
}

private fun findRefsToElement(target: PsiElement, project: Project, file: PsiFile): Collection<PsiReference> {
    val findUsagesManager = (FindManager.getInstance(project) as FindManagerImpl).findUsagesManager
    val handler = findUsagesManager.getFindUsagesHandler(target, true)

    // in case of injected file, use host file to highlight all occurrences of the target in each injected file
    val context = InjectedLanguageManager.getInstance(project).getTopLevelFile(file)

    val searchScope = LocalSearchScope(context)
    return handler?.findReferencesToHighlight(target, searchScope)
        ?: ReferencesSearch.search(target, searchScope, false).findAll()
}

private fun extractDocumentHighlightFromRaw(project: Project,
                                            file: PsiFile,
                                            editor: Editor,
                                            usage: UsageTarget): List<DocumentHighlight>? {
    return if (usage is PsiElement2UsageTargetAdapter) {
        val target = usage.element
        val refs = findRefsToElement(target, project, file)

        return refsToHighlights(target, file, editor, refs)
    } else {
        null
    }
}

private fun refsToHighlights(element: PsiElement,
                             file: PsiFile,
                             editor: Editor,
                             refs: Collection<PsiReference>): List<DocumentHighlight> {
    val detector = ReadWriteAccessDetector.findDetector(element)

    val highlights: MutableList<DocumentHighlight> = mutableListOf()

    if (detector != null) {
        val readRefs = java.util.ArrayList<PsiReference>()
        val writeRefs = java.util.ArrayList<PsiReference>()

        for (ref in refs) {
            if (detector.getReferenceAccess(element, ref) == ReadWriteAccessDetector.Access.Read) {
                readRefs.add(ref)
            } else {
                writeRefs.add(ref)
            }
        }
        addHighlights(highlights, readRefs, editor, DocumentHighlightKind.READ)
        addHighlights(highlights, writeRefs, editor, DocumentHighlightKind.WRITE)
    } else {
        addHighlights(highlights, refs, editor, DocumentHighlightKind.TEXT)
    }

    val range = HighlightUsagesHandler.getNameIdentifierRange(file, element)
    if (range != null) {
        val kind = if (detector != null && detector.isDeclarationWriteAccess(element)) {
            DocumentHighlightKind.WRITE
        } else {
            DocumentHighlightKind.TEXT
        }
        highlights.add(DocumentHighlight(textRangeToRange(editor, range), kind))
    }

    return highlights
}

private fun addHighlights(highlights: MutableList<DocumentHighlight>,
                          refs: Collection<PsiReference>,
                          editor: Editor, kind: Int) {
    val textRanges = java.util.ArrayList<TextRange>(refs.size)
    for (ref in refs) {
        HighlightUsagesHandler.collectRangesToHighlight(ref, textRanges)
    }
    val toAdd = textRanges.map { DocumentHighlight(textRangeToRange(editor, it), kind) }
    highlights.addAll(toAdd)
}

private fun getUsageTargets(editor: Editor, file: PsiFile): Array<UsageTarget>? {
    var usageTargets = UsageTargetUtil.findUsageTargets(editor, file)

    if (usageTargets == null) {
        usageTargets = getUsageTargetsFromNavItem(editor, file)
    }

    if (usageTargets == null) {
        usageTargets = getUsageTargetsFromPolyvariantReference(editor)
    }
    return usageTargets
}

private fun getUsageTargetsFromNavItem(editor: Editor, file: PsiFile): Array<UsageTarget>? {
    var targetElement = findTargetElement(editor) ?: return null
    if (targetElement !== file) {
        if (targetElement !is NavigationItem) {
            targetElement = targetElement.navigationElement
        }
        if (targetElement is NavigationItem) {
            return arrayOf(PsiElement2UsageTargetAdapter(targetElement))
        }
    }
    return null
}

private fun getUsageTargetsFromPolyvariantReference(editor: Editor): Array<UsageTarget>? {
    val ref = TargetElementUtil.findReference(editor)

    if (ref is PsiPolyVariantReference) {
        val results = ref.multiResolve(false)

        if (results.isNotEmpty()) {
            return ContainerUtil.mapNotNull(results, { result ->
                val element = result.element
                if (element == null) null else PsiElement2UsageTargetAdapter(element)
            }, UsageTarget.EMPTY_ARRAY)
        }
    }
    return null
}
