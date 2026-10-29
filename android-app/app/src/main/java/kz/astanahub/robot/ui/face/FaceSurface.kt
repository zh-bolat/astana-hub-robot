package kz.astanahub.robot.ui.face

import android.view.Choreographer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo

/**
 * Поверхность лица: кадр по vsync (Choreographer) → [FaceEngine.update] → перерисовка только фазы отрисовки
 * этого узла (`invalidateDraw`), без рекомпозиции и без записи в snapshot-состояние. В кадре нет аллокаций:
 * колбэк кадра — сам узел, рисование — [FaceRenderer] с заранее созданными Paint/Path.
 *
 * [running] = false (экран скрыт, приложение не на переднем плане) — кадры не запрашиваются.
 */
fun Modifier.faceSurface(engine: FaceEngine, renderer: FaceRenderer, running: Boolean): Modifier =
    this then FaceSurfaceElement(engine, renderer, running)

private data class FaceSurfaceElement(
    val engine: FaceEngine,
    val renderer: FaceRenderer,
    val running: Boolean,
) : ModifierNodeElement<FaceSurfaceNode>() {
    override fun create() = FaceSurfaceNode(engine, renderer, running)

    override fun update(node: FaceSurfaceNode) {
        node.engine = engine
        node.renderer = renderer
        node.setRunning(running)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "faceSurface"
        properties["running"] = running
    }
}

private class FaceSurfaceNode(
    var engine: FaceEngine,
    var renderer: FaceRenderer,
    private var running: Boolean,
) : Modifier.Node(), DrawModifierNode, Choreographer.FrameCallback {

    private var posted = false

    override fun onAttach() {
        if (running) schedule()
    }

    override fun onDetach() {
        cancel()
    }

    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        if (value && isAttached) schedule() else cancel()
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        if (!isAttached || !running) return
        engine.update(frameTimeNanos)
        invalidateDraw()
        schedule()
    }

    override fun ContentDrawScope.draw() {
        drawIntoCanvas { renderer.draw(it.nativeCanvas, engine.pose, size.width, size.height) }
        drawContent()
    }

    private fun schedule() {
        if (posted) return
        posted = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun cancel() {
        if (!posted) return
        posted = false
        Choreographer.getInstance().removeFrameCallback(this)
    }
}
