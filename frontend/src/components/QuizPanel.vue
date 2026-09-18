<script setup lang="ts">
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRepositoryStore } from '../stores/repository'

/**
 * F4 测验面板：选中类后生成 → 逐题作答 → 提交显示得分/错题解析；引用可点击跳转。
 */
const repository = useRepositoryStore()
const { quiz, quizGrade, selectedUnitId, graph } = storeToRefs(repository)

const generating = ref(false)
const submitting = ref(false)
const answers = ref<Record<string, number>>({})

const selectedUnit = computed(() =>
  graph.value?.codeUnits.find((unit) => unit.id === selectedUnitId.value) ?? null,
)

async function onGenerate() {
  if (!selectedUnitId.value) {
    return
  }
  generating.value = true
  answers.value = {}
  try {
    await repository.generateQuiz([selectedUnitId.value])
  } finally {
    generating.value = false
  }
}

async function onSubmit() {
  if (!quiz.value) {
    return
  }
  submitting.value = true
  try {
    await repository.submitQuiz(
      quiz.value.questions.map((question) => ({
        questionId: question.id,
        answerIndex: answers.value[question.id] ?? -1,
      })),
    )
  } finally {
    submitting.value = false
  }
}

function isCorrect(questionId: string): boolean | null {
  if (!quizGrade.value || !quiz.value) {
    return null
  }
  const question = quiz.value.questions.find((q) => q.id === questionId)
  return question != null && answers.value[questionId] === question.answer
}

function jumpToReference(file: string) {
  const unit = graph.value?.codeUnits.find((u) => u.filePath === file)
  if (unit) {
    void repository.selectUnit(unit.id)
  }
}

function shortName(file: string): string {
  return file.split('/').pop() ?? file
}
</script>

<template>
  <div class="quiz-panel">
    <div v-if="!quiz" class="quiz-empty">
      <p v-if="!selectedUnit">先在「类列表」里选中一个类。</p>
      <el-button type="primary" :loading="generating" :disabled="!selectedUnit" @click="onGenerate">
        为 {{ selectedUnit ? selectedUnit.name : '选中类' }} 生成测验
      </el-button>
    </div>

    <div v-else class="quiz-body">
      <div v-for="(question, index) in quiz.questions" :key="question.id" class="quiz-question">
        <p class="question-text">{{ index + 1 }}. {{ question.question }}</p>
        <el-radio-group v-model="answers[question.id]" :disabled="quizGrade != null">
          <el-radio v-for="(option, optionIndex) in question.options" :key="optionIndex" :value="optionIndex">
            {{ option }}
          </el-radio>
        </el-radio-group>

        <div v-if="quizGrade != null" class="question-feedback">
          <el-tag :type="isCorrect(question.id) ? 'success' : 'danger'" size="small">
            {{ isCorrect(question.id) ? '答对' : '答错' }}
          </el-tag>
          <span v-if="!isCorrect(question.id)" class="correct-answer">
            正确答案：{{ question.options[question.answer] }}
          </span>
          <p class="explanation">{{ question.explanation }}</p>
          <el-tag
            class="reference-tag"
            size="small"
            type="info"
            effect="plain"
            :title="question.reference.file"
            @click="jumpToReference(question.reference.file)"
          >
            {{ shortName(question.reference.file) }} {{ question.reference.startLine }}-{{ question.reference.endLine }}
          </el-tag>
        </div>
      </div>

      <div class="quiz-actions">
        <el-button v-if="quizGrade == null" type="primary" :loading="submitting" @click="onSubmit">
          提交
        </el-button>
        <el-tag v-else type="success" size="large">
          得分：{{ quizGrade.correct }} / {{ quizGrade.total }}（{{ Math.round(quizGrade.accuracy * 100) }}%）
        </el-tag>
        <el-button size="small" text type="primary" @click="answers = {}; repository.quiz = null; repository.quizGrade = null">
          重新生成
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.quiz-panel {
  padding: 4px 0;
}

.quiz-empty {
  padding: 16px 8px;
  color: #909399;
  text-align: center;
}

.quiz-body {
  max-height: 620px;
  overflow-y: auto;
}

.quiz-question {
  padding: 10px 12px;
  border-bottom: 1px solid #ebeef5;
}

.question-text {
  font-weight: 600;
  margin: 0 0 8px;
}

.question-feedback {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-start;
}

.correct-answer {
  font-size: 12px;
  color: #67c23a;
}

.explanation {
  font-size: 13px;
  color: #606266;
  margin: 0;
}

.reference-tag {
  cursor: pointer;
}

.quiz-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
}
</style>
