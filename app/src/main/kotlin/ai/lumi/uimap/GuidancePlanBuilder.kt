package ai.lumi.uimap

import ai.lumi.engine.TaskStep
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Raw JSON representation of a bundled/cached guidance plan. */
@JsonClass(generateAdapter = true)
data class GuidancePlanDto(
    val steps: List<GuidanceStepDto>
)

@JsonClass(generateAdapter = true)
data class GuidanceStepDto(
    val stepIndex: Int,
    val targetDescription: String,
    val instructionEn: String,
    val instructionHi: String,   // JSON key kept for backwards compat; maps to instructionNative
    val relativeX: Float = 0f,
    val relativeY: Float = 0f,
    val isLastStep: Boolean = false
)

@Singleton
class GuidancePlanBuilder @Inject constructor() {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val planAdapter = moshi.adapter(GuidancePlanDto::class.java)
    private val listType = com.squareup.moshi.Types.newParameterizedType(List::class.java, GuidanceStepDto::class.java)
    private val listAdapter = moshi.adapter<List<GuidanceStepDto>>(listType)

    /** Convert a stored JSON string to a list of [TaskStep]. */
    fun fromJson(json: String): List<TaskStep>? {
        return try {
            val steps: List<GuidanceStepDto> = if (json.trimStart().startsWith("[")) {
                listAdapter.fromJson(json) ?: return null
            } else {
                planAdapter.fromJson(json)?.steps ?: return null
            }
            steps.map { s ->
                TaskStep(
                    stepIndex = s.stepIndex,
                    targetDescription = s.targetDescription,
                    instructionEn = s.instructionEn,
                    instructionNative = s.instructionHi,   // map JSON field to native instruction
                    relativeX = s.relativeX,
                    relativeY = s.relativeY,
                    isLastStep = s.isLastStep
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "GuidancePlanBuilder.fromJson failed")
            null
        }
    }

    /** Convert a list of [TaskStep] to JSON for storage. */
    fun toJson(steps: List<TaskStep>): String {
        val dto = GuidancePlanDto(
            steps = steps.mapIndexed { i, s ->
                GuidanceStepDto(
                    stepIndex = i,
                    targetDescription = s.targetDescription,
                    instructionEn = s.instructionEn,
                    instructionHi = s.instructionNative,   // save native instruction in the "Hi" JSON slot
                    relativeX = s.relativeX,
                    relativeY = s.relativeY,
                    isLastStep = s.isLastStep
                )
            }
        )
        return planAdapter.toJson(dto)
    }
}
