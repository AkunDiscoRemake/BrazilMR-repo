-- WINDOW · solicitar hand_tracking antes de executar.
local label = zxr.ui.text {text="Aguardando mão direita...", x=.06, y=.15, width=.9, height=.4}
zxr.hand.on("update", function()
    local hand = zxr.hand.get("right")
    if hand.present then
        -- MediaPipe landmark 8 = índice Lua 9.
        local tip = hand.landmarks[9]
        zxr.ui.set(label, {text="Indicador: " .. math.floor(tip.x*100) .. "% / " .. math.floor(tip.y*100) .. "%"})
    else
        zxr.ui.set(label, {text="Mão fora do campo de visão"})
    end
end)
