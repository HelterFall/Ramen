package io.github.helterfall.ramen.runtime.scenario

object ScenarioDemoContent {
    const val SOURCE = """
title "Маяк на острове"
start arrival

scene arrival
background "coast"
show "guide" at center
say "Проводник" "Добро пожаловать на остров. До заката нужно проверить старый маяк."
choice "Осмотреть карту у причала" -> map
choice "Сразу подняться по тропе" -> tower

scene map
background "map_table"
say "Проводник" "На карте отмечена короткая тропа через сосновую рощу."
set has_map = true
jump return_to_path

scene return_to_path
say "Проводник" "Теперь понятно, где искать обходную дорогу."
choice "Подняться к маяку" -> tower
choice "Остаться у причала" -> ending

scene tower
if has_map == true -> tower_with_map
background "cliff"
say "Проводник" "Тропа вывела нас к скалам. Придётся идти осторожно."
jump ending

scene tower_with_map
background "lighthouse"
show "guide" at left
say "Проводник" "Карта помогла найти спокойный путь к маяку."
say "Проводник" "Свет снова виден с моря. Пора возвращаться."
jump ending

scene ending
background "sunset"
say "Проводник" "Проверка завершена. На острове стало немного спокойнее."
end
"""
}
