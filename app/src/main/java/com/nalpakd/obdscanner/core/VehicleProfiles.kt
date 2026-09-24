package com.nalpakd.obdscanner.core

data class VehicleProfile(
    val id: String,
    val displayName: String,
    /** Extra context handed to Claude so it can weigh known model-specific failure patterns. */
    val aiNotes: String
)

object VehicleProfiles {
    val GENERIC = VehicleProfile(
        "generic", "Generic OBD-II vehicle (auto-detect)",
        "No specific vehicle selected. Use the VIN (if present) to identify make, model, year and engine, " +
            "and apply known pattern failures for that platform when relevant."
    )

    val TRAILBLAZER_2007 = VehicleProfile(
        "trailblazer2007", "2007 Chevrolet Trailblazer",
        "2007 Chevrolet Trailblazer (GMT360). Engines: 4.2L Vortec I6 (LL8) or 5.3L V8 (LH6/LM4). Often J1850 VPW or " +
            "early CAN. Known patterns: P0171/P0174 lean from intake/PCV leaks or dirty MAF; P0442/P0455/P0449 EVAP " +
            "vent solenoid (under vehicle near tank) and purge valve; P0128 thermostat; P0014/P0017 exhaust cam " +
            "actuator solenoid / low oil on the 4.2L; P0300-P0306 misfires from coils/plugs; fuel level sender " +
            "errors (P0461-P0463); electric fan clutch and cooling concerns; throttle body carbon causing idle issues; " +
            "U-codes from BCM/instrument cluster ground issues. Vehicle is out of factory warranty."
    )

    val F150_2010 = VehicleProfile(
        "f150_2010", "2010 Ford F-150 (4WD)",
        "2010 Ford F-150 4WD (12th gen). Engines: 4.6L 2V/3V or 5.4L 3V Triton V8. CAN protocol. Known patterns: " +
            "5.4L 3V cam phaser / VCT solenoid / timing chain wear (P0012, P0021, P0016-P0019, tick at idle); " +
            "5.4L 3V spark plug breakage on removal and coil-on-plug misfires (P0300-P0308); P0171/P0174 lean from " +
            "intake manifold / PCV / MAF; P0455/P0456/P1450 EVAP canister purge valve; IWE (integrated wheel end) " +
            "vacuum issues on 4WD (grinding, no codes); P1000 means readiness incomplete after clear (not a fault); " +
            "EGR is not used on these engines. Out of factory warranty."
    )

    val SPORTAGE_2025 = VehicleProfile(
        "sportage2025", "2025 Kia Sportage",
        "2025 Kia Sportage (NQ5). Engines: 2.5L Smartstream GDI I4 or 1.6L turbo GDI hybrid/plug-in hybrid. CAN. " +
            "Generic OBD-II reaches only emissions-related modules. Direct-injection engines can see intake valve " +
            "carbon and high-pressure fuel pump codes (P0087/P0088/P0191). Hybrids may report P0Axx hybrid system " +
            "codes. Vehicle is almost certainly still under Kia factory warranty (5yr/60k basic, 10yr/100k powertrain " +
            "for original owner) - recommend dealer visits and checking open recalls/TSBs rather than DIY repair of " +
            "powertrain faults."
    )

    val ALL = listOf(GENERIC, TRAILBLAZER_2007, F150_2010, SPORTAGE_2025)

    fun byId(id: String?): VehicleProfile = ALL.firstOrNull { it.id == id } ?: GENERIC
}
