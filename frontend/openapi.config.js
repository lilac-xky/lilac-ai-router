import { generateService } from '@umijs/openapi';

generateService({
  requestLibPath: "import request from '@/request';",
  schemaPath: 'http://127.0.0.1:4523/export/openapi/2?version=3.0',
  serversPath: './src',
});
