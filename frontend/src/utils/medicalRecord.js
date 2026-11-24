export function medicalRecordBlocks(record) {
  if (!record) return []
  return [
    { label: '主诉', value: record.chiefComplaint },
    { label: '体格检查', value: record.physicalExam },
    { label: '诊断结论', value: record.diagnosis, strong: true },
    { label: '处方 / 用药建议', value: record.prescription },
    { label: '处置措施', value: record.treatment },
    { label: '医嘱', value: record.advice }
  ].filter((b) => b.value)
}
