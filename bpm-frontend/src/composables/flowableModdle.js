/**
 * Flowable BPMN moddle extension for bpmn-js.
 * Defines Flowable-specific attributes on BPMN elements.
 */
export default {
  name: 'Flowable',
  uri: 'http://flowable.org/bpmn',
  prefix: 'flowable',
  xml: { tagAlias: 'lowerCase' },
  associations: [],
  types: [
    {
      name: 'Assignable',
      extends: ['bpmn:UserTask'],
      properties: [
        { name: 'assignee', isAttr: true, type: 'String' },
        { name: 'candidateGroups', isAttr: true, type: 'String' },
        { name: 'candidateUsers', isAttr: true, type: 'String' },
        { name: 'formKey', isAttr: true, type: 'String' },
        { name: 'skipExpression', isAttr: true, type: 'String' }
      ]
    },
    {
      name: 'Initiator',
      extends: ['bpmn:StartEvent'],
      properties: [
        { name: 'initiator', isAttr: true, type: 'String' },
        { name: 'formKey', isAttr: true, type: 'String' }
      ]
    },
    {
      name: 'TaskListenerList',
      extends: ['bpmn:UserTask'],
      properties: [
        { name: 'taskListener', type: 'TaskListener', isMany: true }
      ]
    },
    {
      name: 'TaskListener',
      properties: [
        { name: 'event', isAttr: true, type: 'String' },
        { name: 'delegateExpression', isAttr: true, type: 'String' },
        { name: 'class', isAttr: true, type: 'String' }
      ]
    }
  ]
}
