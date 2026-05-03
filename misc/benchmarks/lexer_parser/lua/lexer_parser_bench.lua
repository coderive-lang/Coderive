local function is_alpha(c)
  return c == '_' or (c >= 'a' and c <= 'z') or (c >= 'A' and c <= 'Z')
end

local function is_digit(c)
  return c >= '0' and c <= '9'
end

local function lex_parse(text)
  local i = 1
  local n = #text
  local tokens, stmts, depth, max_depth, kind_sum = 0, 0, 0, 0, 0

  while i <= n do
    local c = text:sub(i, i)

    if c:match('%s') then
      if c == '\n' then stmts = stmts + 1 end
      i = i + 1
    elseif c == '/' and i + 1 <= n and text:sub(i + 1, i + 1) == '/' then
      i = i + 2
      while i <= n and text:sub(i, i) ~= '\n' do i = i + 1 end
    elseif c == '/' and i + 1 <= n and text:sub(i + 1, i + 1) == '*' then
      i = i + 2
      while i + 1 <= n and not (text:sub(i, i) == '*' and text:sub(i + 1, i + 1) == '/') do i = i + 1 end
      i = math.min(i + 2, n + 1)
    elseif is_alpha(c) then
      local start = i
      i = i + 1
      while i <= n do
        local d = text:sub(i, i)
        if not (is_alpha(d) or is_digit(d)) then break end
        i = i + 1
      end
      tokens = tokens + 1
      kind_sum = kind_sum + ((i - start) % 97)
    elseif is_digit(c) then
      i = i + 1
      while i <= n do
        local d = text:sub(i, i)
        if not (is_digit(d) or d == '.') then break end
        i = i + 1
      end
      tokens = tokens + 1
      kind_sum = kind_sum + 3
    elseif c == '"' or c == "'" then
      local quote = c
      i = i + 1
      while i <= n do
        local d = text:sub(i, i)
        if d == '\\' then
          i = i + 2
        elseif d == quote then
          i = i + 1
          break
        else
          i = i + 1
        end
      end
      tokens = tokens + 1
      kind_sum = kind_sum + 7
    else
      if c == '(' or c == '[' or c == '{' then
        depth = depth + 1
        if depth > max_depth then max_depth = depth end
      elseif (c == ')' or c == ']' or c == '}') and depth > 0 then
        depth = depth - 1
      end
      if c == ';' then stmts = stmts + 1 end
      tokens = tokens + 1
      kind_sum = kind_sum + 1
      i = i + 1
    end
  end

  return tokens * 31 + stmts * 17 + depth * 13 + max_depth * 7 + kind_sum
end

if #arg < 2 then
  io.stderr:write('usage: lexer_parser_bench.lua <file-list> <iterations>\n')
  os.exit(2)
end

local file_list = arg[1]
local iterations = tonumber(arg[2])

local paths = {}
for line in io.lines(file_list) do
  line = line:gsub('^%s+', ''):gsub('%s+$', '')
  if line ~= '' then table.insert(paths, line) end
end

local digest = 1469598103934665603
for _ = 1, iterations do
  for _, p in ipairs(paths) do
    local f = assert(io.open(p, 'rb'))
    local text = f:read('*a')
    f:close()
    digest = (digest * 1315423911 + lex_parse(text)) % 18446744073709551616
  end
end

print(string.format('DIGEST:%.0f', digest))
